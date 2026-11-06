package com.medconnect.common.web;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcIdempotencyStore implements IdempotencyStore {

	private final JdbcTemplate jdbc;

	public JdbcIdempotencyStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<StoredResponse> reserve(String key, UUID principal) {
		jdbc.update("delete from idempotency_keys where key = ? and principal = ? and created_at < now() - make_interval(secs => ?)",
				key, principal, TTL.toSeconds());
		int inserted = jdbc.update("insert into idempotency_keys(key, principal) values (?, ?) on conflict do nothing",
				key, principal);
		if (inserted == 1) {
			return Optional.empty();
		}
		return jdbc.query("select status, body::text as body from idempotency_keys where key = ? and principal = ?",
				(rs, i) -> new StoredResponse((Integer) rs.getObject("status"), rs.getString("body")), key, principal)
			.stream()
			.findFirst()
			// row expired and was purged between the two statements: treat as still running, client retries
			.or(() -> Optional.of(new StoredResponse(null, null)));
	}

	@Override
	public void complete(String key, UUID principal, int status, String bodyJson) {
		jdbc.update("update idempotency_keys set status = ?, body = ?::jsonb where key = ? and principal = ?",
				status, bodyJson, key, principal);
	}

	@Override
	public void release(String key, UUID principal) {
		jdbc.update("delete from idempotency_keys where key = ? and principal = ? and status is null", key, principal);
	}
}
