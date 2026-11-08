package com.medconnect.care_queue_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.medconnect.care_queue_service.rebuild.QueueRebuilder;
import com.medconnect.care_queue_service.redis.QueueKeys;
import com.sun.net.httpserver.HttpServer;

/** Own context (and Redis) because the rebuild runs on startup. */
@SpringBootTest(properties = { TestContainers.NO_RELAY_POLLING, "medconnect.internal-token=rebuild-token" })
@Import(TestContainers.class)
class RebuildIT {

	static final UUID HIGH = UUID.randomUUID();
	static final UUID LOW = UUID.randomUUID();
	static final UUID ASSIGNED = UUID.randomUUID();
	static final Instant T1 = Instant.now().minus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
	static final Instant T2 = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
	static final AtomicInteger CALLS = new AtomicInteger();
	static final HttpServer SNAPSHOT;

	static {
		try {
			SNAPSHOT = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		SNAPSHOT.createContext("/internal/v1/consultations/waiting", ex -> {
			// first call fails: consultation-service not up yet, the rebuilder must retry in the background
			if (CALLS.incrementAndGet() == 1 || !"rebuild-token".equals(ex.getRequestHeaders().getFirst("X-Internal-Token"))) {
				ex.sendResponseHeaders(503, -1);
				ex.close();
				return;
			}
			byte[] body = """
					[{"consultationId":"%s","patientId":"%s","priority":"HIGH","triageVersion":2,"requestedAt":"%s",
					  "symptoms":{"mainSymptom":"CHEST_PAIN","painLevel":9}},
					 {"consultationId":"%s","patientId":"%s","priority":"LOW","triageVersion":1,"requestedAt":"%s",
					  "symptoms":{"mainSymptom":"COUGH","painLevel":2}},
					 {"consultationId":"%s","patientId":"%s","priority":"MEDIUM","triageVersion":1,"requestedAt":"%s",
					  "symptoms":{"mainSymptom":"FEVER","painLevel":3}}]"""
				.formatted(HIGH, UUID.randomUUID(), T1, LOW, UUID.randomUUID(), T2, ASSIGNED, UUID.randomUUID(), T2)
				.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(200, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		SNAPSHOT.start();
	}

	@DynamicPropertySource
	static void snapshotUrl(DynamicPropertyRegistry registry) {
		registry.add("medconnect.consultation-service.url", () -> "http://127.0.0.1:" + SNAPSHOT.getAddress().getPort());
	}

	@Autowired
	StringRedisTemplate redis;
	@Autowired
	QueueRebuilder rebuilder;

	// Review Focus 4, spec §4 last rule
	@Test
	void rebuildFromSnapshot() throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (!Boolean.TRUE.equals(redis.hasKey(QueueRebuilder.INITIALIZED)) && System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
		}
		assertThat(redis.hasKey(QueueRebuilder.INITIALIZED)).isTrue();
		assertThat(CALLS.get()).isGreaterThanOrEqualTo(2);

		assertThat(redis.opsForZSet().score(QueueKeys.queue("HIGH"), HIGH.toString())).isEqualTo((double) T1.toEpochMilli());
		assertThat(redis.opsForZSet().score(QueueKeys.queue("LOW"), LOW.toString())).isEqualTo((double) T2.toEpochMilli());
		assertThat(redis.opsForHash().get(QueueKeys.entry(HIGH), "triageVersion")).isEqualTo("2");
		assertThat(redis.opsForHash().get(QueueKeys.entry(HIGH), "patientId")).isNotNull();
		assertThat((String) redis.opsForHash().get(QueueKeys.entry(HIGH), "symptoms")).contains("CHEST_PAIN");
		// replay writes no PatientEnqueued: consultation-service already knows these patients are WAITING
		assertThat(redis.opsForList().size(QueueKeys.OUTBOX_PENDING)).isZero();

		// already initialized: a second run (e.g. another instance starting) changes nothing
		redis.opsForZSet().remove(QueueKeys.queue("LOW"), LOW.toString());
		int calls = CALLS.get();
		rebuilder.rebuildIfNeeded();
		assertThat(CALLS.get()).isEqualTo(calls);
		assertThat(redis.opsForZSet().score(QueueKeys.queue("LOW"), LOW.toString())).isNull();

		// Redis lost again; an assignment confirmed before the loss left a tombstone: it must not come back
		redis.getConnectionFactory().getConnection().serverCommands().flushAll();
		redis.opsForValue().set(QueueKeys.tombstone(ASSIGNED), "1");

		rebuilder.rebuildIfNeeded();

		assertThat(redis.hasKey(QueueRebuilder.INITIALIZED)).isTrue();
		assertThat(redis.opsForZSet().score(QueueKeys.queue("MEDIUM"), ASSIGNED.toString())).isNull();
		assertThat(redis.opsForZSet().score(QueueKeys.queue("HIGH"), HIGH.toString())).isNotNull();
	}
}
