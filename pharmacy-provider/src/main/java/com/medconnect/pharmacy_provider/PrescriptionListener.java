package com.medconnect.pharmacy_provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.medconnect.common.messaging.ConsumerTopology;
import com.medconnect.common.messaging.MessagingTopology;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mock third-party pharmacy platform (spec section 3 integration contract): validates the PrescriptionIssued
 * schema, logs eventId and prescriptionId only (no medication details), deduplicates by eventId and acknowledges.
 */
@Component
class PrescriptionListener {

	static final String QUEUE = "pharmacy.prescriptions";
	static final String DELIVERY_FAILED_QUEUE = "prescription.delivery-failed";
	private static final Logger log = LoggerFactory.getLogger(PrescriptionListener.class);
	private static final int SEEN_CAPACITY = 10_000;

	private final JsonMapper json;
	private final OutageToggleController outage;
	// shortcut: in-memory LRU, a restart forgets seen eventIds; fine for a mock, a real pharmacy would persist them
	private final Map<UUID, Boolean> seen = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
			return size() > SEEN_CAPACITY;
		}
	});

	PrescriptionListener(JsonMapper json, OutageToggleController outage) {
		this.json = json;
		this.outage = outage;
	}

	/**
	 * The queue and its DLQ are owned here; both the DLQ (operator replay) and prescription.delivery-failed
	 * (consumed by prescription-service) are bound to the DLX fanout that receives the final failures (D8).
	 */
	@Bean
	static Declarables pharmacyTopology() {
		List<Declarable> all = new ArrayList<>(
				ConsumerTopology.declarables(QUEUE, MessagingTopology.PHARMACY_EXCHANGE, "prescription.issued").getDeclarables());
		FanoutExchange dlx = new FanoutExchange(MessagingTopology.PHARMACY_DLX_EXCHANGE);
		Queue deliveryFailed = new Queue(DELIVERY_FAILED_QUEUE, true);
		all.add(deliveryFailed);
		all.add(BindingBuilder.bind(deliveryFailed).to(dlx));
		all.add(BindingBuilder.bind(new Queue(ConsumerTopology.dlq(QUEUE), true)).to(dlx));
		return new Declarables(all);
	}

	@RabbitListener(queues = QUEUE)
	void onPrescriptionIssued(Message message) {
		JsonNode envelope = json.readTree(message.getBody());
		UUID eventId = validate(envelope);
		if (outage.outage()) {
			throw new IllegalStateException("pharmacy platform unavailable (simulated outage) eventId=" + eventId);
		}
		if (seen.put(eventId, Boolean.TRUE) != null) {
			log.info("Duplicate delivery ignored, already processed: {}", eventId);
			return;
		}
		log.info("Prescription received eventId={} prescriptionId={}", eventId, envelope.get("payload").get("prescriptionId").asString());
	}

	/** @return the eventId; throws (so the message dead-letters after retries) when the payload breaks the schema */
	private UUID validate(JsonNode envelope) {
		try {
			UUID eventId = UUID.fromString(text(envelope, "eventId"));
			JsonNode p = envelope.get("payload");
			if (p == null || !p.isObject()) {
				throw new IllegalArgumentException("payload missing");
			}
			for (String id : List.of("prescriptionId", "consultationId", "patientId", "doctorId")) {
				UUID.fromString(text(p, id));
			}
			text(p, "issuedAt");
			JsonNode items = p.get("items");
			if (items == null || !items.isArray() || items.isEmpty()) {
				throw new IllegalArgumentException("items missing or empty");
			}
			for (JsonNode item : items) {
				text(item, "medicationName");
				text(item, "dosage");
				text(item, "frequency");
				JsonNode days = item.get("durationDays");
				if (days == null || !days.isInt() || days.asInt() < 1) {
					throw new IllegalArgumentException("durationDays invalid");
				}
			}
			return eventId;
		}
		catch (RuntimeException ex) {
			log.warn("Rejected invalid PrescriptionIssued: {}", ex.getMessage());
			throw ex;
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = (node == null) ? null : node.get(field);
		if (value == null || !value.isString() || value.asString().isBlank()) {
			throw new IllegalArgumentException("field " + field + " missing");
		}
		return value.asString();
	}
}
