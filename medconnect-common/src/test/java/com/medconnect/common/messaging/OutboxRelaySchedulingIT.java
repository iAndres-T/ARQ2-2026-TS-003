package com.medconnect.common.messaging;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import com.medconnect.common.Await;
import com.medconnect.common.TestContainersConfig;

/** The relay must keep publishing while service jobs (aging, no-show, lease recovery) occupy the scheduler. */
@SpringBootTest
@Import({ TestContainersConfig.class, OutboxRelaySchedulingIT.BusyJob.class })
class OutboxRelaySchedulingIT {

	@Autowired OutboxWriter writer;
	@Autowired TransactionTemplate tx;
	@Autowired JdbcTemplate jdbc;

	@Test
	void relayIsNotStarvedBySlowScheduledJobs() throws Exception {
		tx.executeWithoutResult(s -> writer.append("medconnect.events", "audit.anything", "TestCreated", UUID.randomUUID(), 1, null, Map.of()));

		// routed nowhere in this context, so it must at least have been attempted by the background relay
		Await.until(Duration.ofSeconds(3), () -> jdbc.queryForObject(
				"select count(*) from outbox where published_at is not null or attempts > 0", Integer.class) == 1);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class BusyJob {

		@Scheduled(fixedDelay = 10)
		void hogTheSchedulerThread() throws InterruptedException {
			Thread.sleep(10_000);
		}
	}
}
