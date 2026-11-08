package com.medconnect.care_queue_service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.medconnect.care_queue_service.rebuild.QueueRebuilder;
import com.medconnect.care_queue_service.redis.QueueKeys;

/** Separate context from RebuildIT so its 1 s tick cannot race that test's manual Redis wipe; reuses its snapshot server. */
@SpringBootTest(properties = { TestContainers.NO_RELAY_POLLING, "medconnect.internal-token=rebuild-token",
		"medconnect.queue.rebuild-check-interval=PT1S" })
@Import(TestContainers.class)
class RebuildPeriodicIT {

	@DynamicPropertySource
	static void snapshotUrl(DynamicPropertyRegistry registry) {
		registry.add("medconnect.consultation-service.url", () -> "http://127.0.0.1:" + RebuildIT.SNAPSHOT.getAddress().getPort());
	}

	@Autowired
	StringRedisTemplate redis;

	// Redis lost while running: the periodic check (not just startup) must rebuild
	@Test
	void periodicCheckRebuildsAfterRedisLoss() throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (!Boolean.TRUE.equals(redis.hasKey(QueueRebuilder.INITIALIZED)) && System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
		}
		redis.getConnectionFactory().getConnection().serverCommands().flushAll(); // Redis lost: queues, entries and the marker
		deadline = System.currentTimeMillis() + 15_000;
		while (redis.opsForZSet().score(QueueKeys.queue("HIGH"), RebuildIT.HIGH.toString()) == null && System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
		}
		assertThat(redis.opsForZSet().score(QueueKeys.queue("HIGH"), RebuildIT.HIGH.toString())).isNotNull();
	}
}
