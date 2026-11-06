package com.medconnect.common.messaging;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Consumer-side deduplication by eventId; call inside the consumer's transaction. */
public class InboxGuard {

	private final JdbcTemplate jdbc;

	public InboxGuard(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @return true the first time this consumer sees the event, false for a duplicate delivery */
	public boolean firstTime(UUID eventId, String consumer) {
		return jdbc.update("insert into processed_events(event_id, consumer) values (?, ?) on conflict do nothing",
				eventId, consumer) == 1;
	}
}
