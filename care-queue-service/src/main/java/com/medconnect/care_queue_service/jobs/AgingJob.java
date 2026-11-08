package com.medconnect.care_queue_service.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.medconnect.care_queue_service.app.QueueService;
import com.medconnect.care_queue_service.redis.QueueKeys;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Every 60 s: LOW patients waiting longer than {@code medconnect.queue.aging-threshold} move to MEDIUM keeping their
 * score, via enqueue_or_move.lua with reason AGING and the stored triageVersion (R-F4-04, S-F4-08). Never to HIGH.
 * Assigned or cancelled consultations are tombstoned, so the script ignores them.
 */
@Component
public class AgingJob {

	private final StringRedisTemplate redis;
	private final QueueService queues;
	private final Duration threshold;

	AgingJob(StringRedisTemplate redis, QueueService queues,
			@Value("${medconnect.queue.aging-threshold:90m}") Duration threshold) {
		this.redis = redis;
		this.queues = queues;
		this.threshold = threshold;
	}

	@Scheduled(fixedDelayString = "${medconnect.queue.aging-interval:PT60S}")
	@SchedulerLock(name = "care-queue-aging", lockAtMostFor = "PT5M")
	public void run() {
		promote();
	}

	public void promote() {
		long max = System.currentTimeMillis() - threshold.toMillis();
		// exclusive bound: (now - threshold
		Set<String> aged = redis.opsForZSet().rangeByScore(QueueKeys.queue("LOW"), Double.NEGATIVE_INFINITY, max - 1, 0, 1000);
		for (String cid : (aged == null) ? Set.<String>of() : aged) {
			UUID consultationId = UUID.fromString(cid);
			List<Object> e = redis.opsForHash().multiGet(QueueKeys.entry(consultationId), List.of("triageVersion", "requestedAt"));
			if (e.get(0) == null || e.get(1) == null) {
				continue; // removed meanwhile
			}
			queues.enqueueOrMove(new QueueService.Move(consultationId, "MEDIUM", Integer.parseInt((String) e.get(0)),
					Instant.ofEpochMilli(Long.parseLong((String) e.get(1))), "AGING", null, null), null, null, null);
		}
	}
}
