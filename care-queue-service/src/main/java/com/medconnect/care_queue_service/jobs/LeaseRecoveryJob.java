package com.medconnect.care_queue_service.jobs;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.medconnect.care_queue_service.outbox.OutboxRecords;
import com.medconnect.care_queue_service.redis.QueueKeys;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Every 5 s: claims whose lease expired without a confirm go back to their original queue with their original
 * score (S-F4-12, R-F4-03); the doctor goes back to FREE locally. PatientEnqueued uses reason INITIAL.
 */
@Component
public class LeaseRecoveryJob {

	private static final Logger log = LoggerFactory.getLogger(LeaseRecoveryJob.class);

	private final StringRedisTemplate redis;
	private final RedisScript<String> requeueExpiredScript;
	private final OutboxRecords outbox;

	LeaseRecoveryJob(StringRedisTemplate redis, RedisScript<String> requeueExpiredScript, OutboxRecords outbox) {
		this.redis = redis;
		this.requeueExpiredScript = requeueExpiredScript;
		this.outbox = outbox;
	}

	@Scheduled(fixedDelayString = "${medconnect.queue.lease-recovery-interval:PT5S}")
	@SchedulerLock(name = "care-queue-lease-recovery", lockAtMostFor = "PT1M")
	public void run() {
		recover();
	}

	public void recover() {
		long now = System.currentTimeMillis();
		Set<String> expired = redis.opsForZSet().rangeByScore(QueueKeys.CLAIMED, Double.NEGATIVE_INFINITY, now, 0, 1000);
		for (String cid : (expired == null) ? Set.<String>of() : expired) {
			UUID consultationId = UUID.fromString(cid);
			Object requestedAt = redis.opsForHash().get(QueueKeys.entry(consultationId), "requestedAt");
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("consultationId", consultationId);
			payload.put("queue", null); // queue, triageVersion, previousQueue and reason are set by the script
			payload.put("previousQueue", null);
			payload.put("reason", "INITIAL");
			payload.put("triageVersion", null);
			payload.put("requestedAt", (requestedAt == null) ? null : Instant.ofEpochMilli(Long.parseLong((String) requestedAt)));
			payload.put("enqueuedAt", Instant.now());
			String record = outbox.build("queue.patient-enqueued", "PatientEnqueued", consultationId, 0, null, null, null, payload);
			String result = redis.execute(requeueExpiredScript,
					List.of(QueueKeys.entry(consultationId), QueueKeys.CLAIMED, QueueKeys.queue("HIGH"), QueueKeys.queue("MEDIUM"),
							QueueKeys.queue("LOW"), QueueKeys.OUTBOX_PENDING, QueueKeys.DOCTORS_FREE),
					cid, String.valueOf(now), record);
			log.info("lease expired consultation={} -> {}", cid, result);
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
	static class LockConfig {

		@Bean
		LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
			return new RedisLockProvider(connectionFactory, "care-queue");
		}
	}
}
