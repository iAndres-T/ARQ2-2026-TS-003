package com.medconnect.care_queue_service.rebuild;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.medconnect.care_queue_service.app.QueueService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import tools.jackson.databind.JsonNode;

/**
 * Spec §4 last rule: Redis is a rebuildable working index. When {@code queue:initialized} is absent (first start or
 * Redis lost), the WAITING consultations are loaded from consultation-service's internal snapshot (never its tables)
 * and replayed through {@code enqueue_or_move.lua} without outbox events. Runs in the background with backoff, so an
 * unreachable consultation-service never blocks startup.
 */
@Component
public class QueueRebuilder {

	public static final String INITIALIZED = "queue:initialized";
	private static final Logger log = LoggerFactory.getLogger(QueueRebuilder.class);
	private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

	private final StringRedisTemplate redis;
	private final QueueService queues;
	private final RestClient http;
	private final String token;

	QueueRebuilder(StringRedisTemplate redis, QueueService queues,
			@Value("${medconnect.consultation-service.url}") String baseUrl, @Value("${medconnect.internal-token}") String token) {
		this.redis = redis;
		this.queues = queues;
		this.token = token;
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
		factory.setReadTimeout(Duration.ofSeconds(30));
		this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
	}

	@EventListener(ApplicationReadyEvent.class)
	void onStartup() {
		Thread.ofVirtual().name("queue-rebuild").start(() -> {
			Duration backoff = Duration.ofSeconds(1);
			while (true) {
				try {
					rebuildIfNeeded();
					return;
				}
				catch (RuntimeException ex) {
					log.warn("Queue rebuild failed, retrying in {}: {}", backoff, ex.toString());
				}
				try {
					Thread.sleep(backoff);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return;
				}
				backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
			}
		});
	}

	/** Redis may be lost while running: re-check periodically; the startup loop only covers the first start. */
	@Scheduled(initialDelayString = "${medconnect.queue.rebuild-check-interval:PT60S}", fixedDelayString = "${medconnect.queue.rebuild-check-interval:PT60S}")
	@SchedulerLock(name = "care-queue-rebuild", lockAtMostFor = "PT5M")
	void periodicCheck() {
		try {
			rebuildIfNeeded();
		}
		catch (RuntimeException ex) {
			log.warn("Periodic queue rebuild failed, will retry on next tick: {}", ex.toString());
		}
	}

	/** Throws when the snapshot cannot be loaded; {@code queue:initialized} is set only after a full replay. */
	public void rebuildIfNeeded() {
		if (Boolean.TRUE.equals(redis.hasKey(INITIALIZED))) {
			return;
		}
		JsonNode snapshot = http.get().uri("/internal/v1/consultations/waiting").header("X-Internal-Token", token)
			.retrieve().body(JsonNode.class);
		int restored = 0;
		for (JsonNode row : snapshot) {
			try {
				JsonNode symptoms = row.get("symptoms");
				String result = queues.restore(new QueueService.Move(UUID.fromString(row.required("consultationId").asString()),
						row.required("priority").asString(), row.required("triageVersion").asInt(),
						Instant.parse(row.required("requestedAt").asString()), "INITIAL",
						UUID.fromString(row.required("patientId").asString()),
						(symptoms == null || symptoms.isNull()) ? null : symptoms.toString()));
				if ("ENQUEUED".equals(result)) {
					restored++;
				}
			}
			catch (DataAccessException ex) {
				throw ex; // Redis trouble: retry the whole rebuild, never mark a partial replay as done
			}
			catch (RuntimeException ex) {
				// one malformed row must not block the whole rebuild
				log.warn("Skipping invalid snapshot row: {}", ex.toString());
			}
		}
		redis.opsForValue().set(INITIALIZED, Instant.now().toString());
		log.info("Queue rebuilt from consultation-service snapshot: {} of {} WAITING consultations restored", restored,
				snapshot.size());
	}
}
