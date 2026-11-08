package com.medconnect.care_queue_service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.care_queue_service.redis.QueueKeys;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code GET /api/v1/queue/stats} plus the gauges {@code medconnect_queue_depth{level}} and
 * {@code medconnect_queue_oldest_wait_seconds{level}} (read from Redis on each scrape).
 */
@RestController
public class StatsController {

	private final StringRedisTemplate redis;

	StatsController(StringRedisTemplate redis, MeterRegistry meters) {
		this.redis = redis;
		for (String level : QueueKeys.PRIORITIES) {
			Gauge.builder("medconnect.queue.depth", () -> level(level).waiting()).tag("level", level).register(meters);
			Gauge.builder("medconnect.queue.oldest.wait.seconds", () -> level(level).oldestWaitSeconds())
				.tag("level", level)
				.register(meters);
		}
	}

	@GetMapping("/api/v1/queue/stats")
	public Stats stats() {
		Map<String, Level> queues = new LinkedHashMap<>();
		QueueKeys.PRIORITIES.forEach(p -> queues.put(p, level(p)));
		Long free = redis.opsForSet().size(QueueKeys.DOCTORS_FREE);
		return new Stats(Instant.now(), queues, (free == null) ? 0 : free);
	}

	private Level level(String priority) {
		String key = QueueKeys.queue(priority);
		Long waiting = redis.opsForZSet().zCard(key);
		Set<TypedTuple<String>> oldest = redis.opsForZSet().rangeWithScores(key, 0, 0);
		long oldestWait = (oldest == null || oldest.isEmpty()) ? 0
				: Math.max(0, (System.currentTimeMillis() - oldest.iterator().next().getScore().longValue()) / 1000);
		return new Level((waiting == null) ? 0 : waiting, oldestWait);
	}

	public record Stats(Instant generatedAt, Map<String, Level> queues, long freeDoctors) {
	}

	public record Level(long waiting, long oldestWaitSeconds) {
	}
}
