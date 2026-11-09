package com.medconnect.audit_service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.medconnect.common.messaging.ConsumerTopology;
import com.medconnect.common.messaging.EventEnvelope;
import com.medconnect.common.messaging.MessagingTopology;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Stores every event from both exchanges; the unique eventId makes redelivery a no-op (acked and skipped). */
@Component
class AuditListener {

	static final String QUEUE = "audit.all";

	private final AuditRepository repository;
	private final JsonMapper json;

	AuditListener(AuditRepository repository, JsonMapper json) {
		this.repository = repository;
		this.json = json;
	}

	/** One queue bound with '#' to both exchanges. */
	@Bean
	static Declarables auditConsumerTopology() {
		List<Declarable> all = new ArrayList<>(
				ConsumerTopology.declarables(QUEUE, MessagingTopology.EVENTS_EXCHANGE, "#").getDeclarables());
		ConsumerTopology.declarables(QUEUE, MessagingTopology.PHARMACY_EXCHANGE, "#").getDeclarables().stream()
			.filter(d -> d instanceof Binding b && MessagingTopology.PHARMACY_EXCHANGE.equals(b.getExchange()))
			.forEach(all::add);
		return new Declarables(all);
	}

	@RabbitListener(queues = QUEUE)
	void onEvent(Message message) {
		EventEnvelope e = json.readValue(message.getBody(), EventEnvelope.class);
		repository.insert(e.eventId(), e.eventType(), e.aggregateId(), e.actorId(), e.occurredAt(), e.producer(),
				e.correlationId(), minimise(e.payload()));
	}

	/** Data minimisation (ADR-005): symptom free text and clinical summaries never enter the audit store. */
	static JsonNode minimise(JsonNode payload) {
		if (payload instanceof ObjectNode original) {
			ObjectNode copy = original.deepCopy();
			copy.remove("clinicalSummary");
			if (copy.get("symptoms") instanceof ObjectNode symptoms) {
				symptoms.remove("description");
			}
			return copy;
		}
		return payload;
	}
}
