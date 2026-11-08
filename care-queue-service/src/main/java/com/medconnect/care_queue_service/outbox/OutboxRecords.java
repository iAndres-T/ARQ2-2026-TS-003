package com.medconnect.care_queue_service.outbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.medconnect.common.messaging.EventEnvelope;
import com.medconnect.common.messaging.MessagingTopology;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the JSON records pushed to {@code outbox:pending} by the Lua scripts (D5):
 * {@code {exchange, routingKey, traceparent, envelope}}. The traceparent (D12) is captured when a span is current.
 */
@Component
public class OutboxRecords {

	public static final String PRODUCER = "care-queue-service";

	private final JsonMapper json;
	private final ObjectProvider<Tracer> tracer;

	OutboxRecords(JsonMapper json, ObjectProvider<Tracer> tracer) {
		this.json = json;
		this.tracer = tracer;
	}

	public String build(String routingKey, String eventType, UUID aggregateId, long aggregateVersion, UUID correlationId,
			UUID causationId, UUID actorId, Map<String, ?> payload) {
		EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), eventType, 1, Instant.now(), PRODUCER, aggregateId,
				aggregateVersion, (correlationId != null) ? correlationId : UUID.randomUUID(), causationId, actorId,
				json.valueToTree(payload));
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("exchange", MessagingTopology.EVENTS_EXCHANGE);
		record.put("routingKey", routingKey);
		record.put("traceparent", traceparent());
		record.put("envelope", envelope);
		return json.writeValueAsString(record);
	}

	private String traceparent() {
		Tracer t = tracer.getIfAvailable();
		Span span = (t == null) ? null : t.currentSpan();
		return (span == null) ? null : "00-" + span.context().traceId() + "-" + span.context().spanId()
				+ (Boolean.FALSE.equals(span.context().sampled()) ? "-00" : "-01");
	}
}
