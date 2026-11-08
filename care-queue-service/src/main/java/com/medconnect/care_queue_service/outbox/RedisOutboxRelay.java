package com.medconnect.care_queue_service.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.medconnect.care_queue_service.redis.QueueKeys;
import com.medconnect.common.messaging.MessagingProperties;
import com.rabbitmq.client.ReturnCallback;
import com.rabbitmq.client.ReturnListener;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis outbox relay (D5, ADR-002 semantics): moves records from {@code outbox:pending} to {@code outbox:inflight}
 * (LMOVE), publishes them persistent + mandatory with publisher confirms, then LREMs them. Unroutable records go
 * back to pending and the relay backs off; nothing is dropped. A Redis lock key with TTL keeps one relay active
 * across instances; every run of the holder first returns inflight leftovers (failed run or crashed relay) to pending.
 * Delivery is at-least-once: a crash after the confirm and before the LREM republishes (consumers dedupe).
 */
@Component
public class RedisOutboxRelay implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(RedisOutboxRelay.class);
	private static final int BATCH_SIZE = 100;
	private static final int MAX_BATCHES_PER_RUN = 20;
	private static final long CONFIRM_TIMEOUT_MS = 10_000;
	private static final Duration LOCK_TTL = Duration.ofSeconds(30);
	private static final long MAX_BACKOFF_MS = 30_000;
	/** Compare-and-expire: renews the lock only if this relay still owns it. */
	private static final RedisScript<Long> RENEW_LOCK = RedisScript.of("""
			if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
			return 0""", Long.class);

	private final StringRedisTemplate redis;
	private final RabbitTemplate rabbit;
	private final JsonMapper json;
	private final Duration pollInterval;
	private final String owner = UUID.randomUUID().toString();
	private final DefaultMessagePropertiesConverter propertiesConverter = new DefaultMessagePropertiesConverter();
	private volatile ScheduledExecutorService executor;
	private long backoffUntil;
	private int failures;

	RedisOutboxRelay(StringRedisTemplate redis, RabbitTemplate rabbit, JsonMapper json, MessagingProperties properties,
			MeterRegistry meters) {
		this.redis = redis;
		Gauge.builder("medconnect.outbox.pending", () -> {
			Long size = redis.opsForList().size(QueueKeys.OUTBOX_PENDING);
			return (size == null) ? 0 : size;
		}).register(meters);
		this.rabbit = rabbit;
		this.json = json;
		this.pollInterval = properties.outboxPollInterval();
	}

	/** @return number of records published (confirmed), or -1 when nothing could be routed */
	public int publishPending() {
		int total = 0;
		for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
			int result = publishBatch();
			if (result < 0) {
				return (total == 0) ? -1 : total;
			}
			total += result;
			if (result < BATCH_SIZE) {
				break; // short batch: drained (keep draining only while batches come back full)
			}
		}
		return total;
	}

	private int publishBatch() {
		if (!holdLock()) {
			return 0;
		}
		// the lock holder owns everything in flight: leftovers of a failed run (ours or a dead holder's) go first
		recoverInflight();
		List<String> batch = new ArrayList<>();
		for (int i = 0; i < BATCH_SIZE; i++) {
			String record = redis.opsForList().move(QueueKeys.OUTBOX_PENDING, Direction.LEFT, QueueKeys.OUTBOX_INFLIGHT, Direction.RIGHT);
			if (record == null) {
				break;
			}
			batch.add(record);
		}
		if (batch.isEmpty()) {
			return 0;
		}
		List<Parsed> parsed = new ArrayList<>();
		for (String record : batch) {
			try {
				parsed.add(parse(record));
			}
			catch (RuntimeException ex) {
				log.error("Unparseable outbox record moved to {}", QueueKeys.OUTBOX_POISON, ex);
				redis.opsForList().rightPush(QueueKeys.OUTBOX_POISON, record);
				redis.opsForList().remove(QueueKeys.OUTBOX_INFLIGHT, 1, record);
			}
		}
		Set<String> returned = publish(parsed);
		int published = 0;
		for (Parsed p : parsed) {
			if (returned.contains(p.eventId())) {
				log.warn("Outbox event {} ({}) is unroutable on {}/{}; will retry", p.eventId(), p.eventType(),
						p.exchange(), p.routingKey());
				redis.opsForList().rightPush(QueueKeys.OUTBOX_PENDING, p.raw()); // push first: a crash duplicates, never loses
			}
			else {
				published++;
			}
			redis.opsForList().remove(QueueKeys.OUTBOX_INFLIGHT, 1, p.raw());
		}
		return (published == 0 && !returned.isEmpty()) ? -1 : published;
	}

	private boolean holdLock() {
		if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(QueueKeys.RELAY_LOCK, owner, LOCK_TTL))) {
			return true;
		}
		return Long.valueOf(1).equals(redis.execute(RENEW_LOCK, List.of(QueueKeys.RELAY_LOCK), owner,
				String.valueOf(LOCK_TTL.toMillis())));
	}

	/** Returns inflight records to the head of pending, keeping their order. */
	private void recoverInflight() {
		while (redis.opsForList().move(QueueKeys.OUTBOX_INFLIGHT, Direction.RIGHT, QueueKeys.OUTBOX_PENDING, Direction.LEFT) != null) {
			// keep moving
		}
	}

	private Parsed parse(String raw) {
		JsonNode record = json.readTree(raw);
		JsonNode envelope = record.required("envelope");
		JsonNode traceparent = record.get("traceparent");
		JsonNode correlationId = envelope.get("correlationId");
		return new Parsed(raw, record.required("exchange").asString(), record.required("routingKey").asString(),
				(traceparent == null || traceparent.isNull()) ? null : traceparent.asString(),
				envelope.required("eventId").asString(), envelope.required("eventType").asString(),
				(correlationId == null || correlationId.isNull()) ? null : correlationId.asString(),
				json.writeValueAsBytes(envelope));
	}

	/** @return eventIds the broker returned as unroutable; returns arrive before the confirm */
	private Set<String> publish(List<Parsed> records) {
		if (records.isEmpty()) {
			return Set.of();
		}
		return rabbit.execute(channel -> {
			Set<String> returned = ConcurrentHashMap.newKeySet();
			ReturnListener listener = channel.addReturnListener((ReturnCallback) r -> returned.add(r.getProperties().getMessageId()));
			try {
				for (Parsed p : records) {
					channel.basicPublish(p.exchange(), p.routingKey(), true,
							propertiesConverter.fromMessageProperties(properties(p), StandardCharsets.UTF_8.name()), p.body());
				}
				channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
				return returned;
			}
			finally {
				channel.removeReturnListener(listener);
			}
		});
	}

	private static MessageProperties properties(Parsed p) {
		MessageProperties props = new MessageProperties();
		props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		props.setContentEncoding(StandardCharsets.UTF_8.name());
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		props.setMessageId(p.eventId());
		props.setType(p.eventType());
		props.setCorrelationId(p.correlationId());
		if (p.traceparent() != null) {
			props.setHeader("traceparent", p.traceparent());
		}
		return props;
	}

	private void publishSafely() {
		if (System.currentTimeMillis() < backoffUntil) {
			return;
		}
		try {
			if (publishPending() >= 0) {
				failures = 0;
				return;
			}
		}
		catch (RuntimeException ex) {
			log.warn("Redis outbox relay run failed; records stay in Redis and are retried", ex);
		}
		failures = Math.min(failures + 1, 15);
		backoffUntil = System.currentTimeMillis() + Math.min(1_000L << (failures - 1), MAX_BACKOFF_MS);
	}

	@Override
	public void start() {
		executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "redis-outbox-relay"));
		executor.scheduleWithFixedDelay(this::publishSafely, pollInterval.toMillis(), pollInterval.toMillis(),
				TimeUnit.MILLISECONDS);
	}

	@Override
	public void stop() {
		ScheduledExecutorService current = executor;
		executor = null;
		if (current != null) {
			current.shutdown();
			try {
				current.awaitTermination(5, TimeUnit.SECONDS); // let an in-progress run finish its LREMs
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Override
	public boolean isRunning() {
		return executor != null;
	}

	private record Parsed(String raw, String exchange, String routingKey, String traceparent, String eventId,
			String eventType, String correlationId, byte[] body) {
	}
}
