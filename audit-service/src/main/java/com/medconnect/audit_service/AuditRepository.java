package com.medconnect.audit_service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Insert and read only; the database itself rejects UPDATE/DELETE/TRUNCATE. */
@Repository
class AuditRepository {

	record Row(UUID eventId, String eventType, UUID aggregateId, UUID actorId, Instant occurredAt, JsonNode payload) {
	}

	record Filter(UUID aggregateId, String eventType, UUID actorId, Instant from, Instant to, Instant afterAt, UUID afterId) {
	}

	private final JdbcTemplate jdbc;
	private final JsonMapper json;

	AuditRepository(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Returns false when the eventId was already stored (duplicate delivery). */
	boolean insert(UUID eventId, String eventType, UUID aggregateId, UUID actorId, Instant occurredAt, String producer,
			UUID correlationId, JsonNode payload) {
		return jdbc.update("""
				insert into audit_event (event_id, event_type, aggregate_id, actor_id, occurred_at, producer, correlation_id, payload)
				values (?, ?, ?, ?, ?, ?, ?, ?::jsonb) on conflict (event_id) do nothing""",
				eventId, eventType, aggregateId, actorId, Timestamp.from(occurredAt), producer, correlationId,
				json.writeValueAsString(payload)) == 1;
	}

	List<Row> find(Filter f, int limit) {
		StringBuilder sql = new StringBuilder(
				"select event_id, event_type, aggregate_id, actor_id, occurred_at, payload::text from audit_event where true");
		List<Object> args = new ArrayList<>();
		if (f.aggregateId() != null) {
			sql.append(" and aggregate_id = ?");
			args.add(f.aggregateId());
		}
		if (f.eventType() != null) {
			sql.append(" and event_type = ?");
			args.add(f.eventType());
		}
		if (f.actorId() != null) {
			sql.append(" and actor_id = ?");
			args.add(f.actorId());
		}
		if (f.from() != null) {
			sql.append(" and occurred_at >= ?");
			args.add(Timestamp.from(f.from()));
		}
		if (f.to() != null) {
			sql.append(" and occurred_at <= ?");
			args.add(Timestamp.from(f.to()));
		}
		if (f.afterAt() != null) {
			sql.append(" and (occurred_at, event_id) > (?, ?)");
			args.add(Timestamp.from(f.afterAt()));
			args.add(f.afterId());
		}
		sql.append(" order by occurred_at, event_id limit ?");
		args.add(limit);
		return jdbc.query(sql.toString(), (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2),
				rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant(),
				json.readTree(rs.getString(6))), args.toArray());
	}
}
