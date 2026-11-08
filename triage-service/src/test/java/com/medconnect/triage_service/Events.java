package com.medconnect.triage_service;

import java.time.Instant;
import java.util.UUID;

/** Test helper: builds spec section 3 envelopes as JSON. */
final class Events {

	static final String SYMPTOMS_CHEST = symptoms("CHEST_PAIN", 9);
	static final String SYMPTOMS_COUGH = symptoms("COUGH", 2);
	static final String SYMPTOMS_FEVER = symptoms("FEVER", 8);

	private Events() {
	}

	static String symptoms(String main, int pain) {
		return """
				{"mainSymptom":"%s","painLevel":%d,"symptomDuration":{"value":30,"unit":"MINUTES"},"age":58,
				 "redFlags":{"difficultyBreathing":false,"lossOfConsciousness":false},"description":null}""".formatted(main, pain);
	}

	static String envelope(String type, UUID eventId, UUID consultationId, long aggregateVersion, String payloadFields) {
		return """
				{"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s","producer":"test","aggregateId":"%s",
				 "aggregateVersion":%d,"correlationId":"%s","causationId":null,"actorId":null,
				 "payload":{"consultationId":"%s",%s}}""".formatted(eventId, type, Instant.now(), consultationId, aggregateVersion,
				UUID.randomUUID(), consultationId, payloadFields);
	}

	static String requested(UUID eventId, UUID consultationId, UUID patientId, String symptoms) {
		return envelope("ConsultationRequested", eventId, consultationId, 1,
				"\"patientId\":\"%s\",\"symptoms\":%s,\"requestedAt\":\"2026-12-15T10:15:30Z\"".formatted(patientId, symptoms));
	}

	static String symptomsUpdated(UUID eventId, UUID consultationId, UUID patientId, long aggregateVersion, String symptoms) {
		return envelope("SymptomsUpdated", eventId, consultationId, aggregateVersion,
				"\"patientId\":\"%s\",\"symptoms\":%s,\"updatedAt\":\"%s\"".formatted(patientId, symptoms, Instant.now()));
	}
}
