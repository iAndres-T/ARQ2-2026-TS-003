package com.medconnect.common.messaging;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import tools.jackson.databind.json.JsonMapper;

/** Writes events to the outbox table inside the caller's transaction (ADR-002). */
public class OutboxWriter {

	public static final String CORRELATION_ID_MDC = "correlationId";
	public static final String CAUSATION_ID_MDC = "causationId";

	private final JdbcTemplate jdbc;
	private final JsonMapper json;
	private final String producer;
	private final ObjectProvider<Tracer> tracer;

	public OutboxWriter(JdbcTemplate jdbc, JsonMapper json, String producer, ObjectProvider<Tracer> tracer) {
		this.jdbc = jdbc;
		this.json = json;
		this.producer = producer;
		this.tracer = tracer;
	}

	public UUID append(String exchange, String routingKey, String eventType, UUID aggregateId, long aggregateVersion,
			UUID actorId, Object payload) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("OutboxWriter.append must run inside the state-changing transaction");
		}
		UUID eventId = UUID.randomUUID();
		EventEnvelope envelope = new EventEnvelope(eventId, eventType, 1, Instant.now(), producer, aggregateId,
				aggregateVersion, mdcUuidOrRandom(CORRELATION_ID_MDC), mdcUuid(CAUSATION_ID_MDC), actorId,
				json.valueToTree(payload));
		jdbc.update("insert into outbox(id, exchange, routing_key, envelope, traceparent) values (?, ?, ?, ?::jsonb, ?)",
				eventId, exchange, routingKey, json.writeValueAsString(envelope), traceparent());
		return eventId;
	}

	private String traceparent() {
		Tracer t = tracer.getIfAvailable();
		Span span = (t == null) ? null : t.currentSpan();
		if (span == null) {
			return null;
		}
		return "00-" + span.context().traceId() + "-" + span.context().spanId() + (Boolean.FALSE.equals(span.context().sampled()) ? "-00" : "-01");
	}

	private static UUID mdcUuidOrRandom(String key) {
		UUID value = mdcUuid(key);
		return (value != null) ? value : UUID.randomUUID();
	}

	private static UUID mdcUuid(String key) {
		String value = MDC.get(key);
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
