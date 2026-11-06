package com.medconnect.common.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Deletes published outbox rows and inbox entries older than 7 days (ADR-002) and expired idempotency keys. */
public class OutboxPurgeJob {

	private final JdbcTemplate jdbc;

	public OutboxPurgeJob(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Scheduled(cron = "0 0 * * * *")
	public void purge() {
		jdbc.update("delete from outbox where published_at < now() - interval '7 days'");
		jdbc.update("delete from processed_events where processed_at < now() - interval '7 days'");
		jdbc.update("delete from idempotency_keys where created_at < now() - interval '24 hours'");
	}
}
