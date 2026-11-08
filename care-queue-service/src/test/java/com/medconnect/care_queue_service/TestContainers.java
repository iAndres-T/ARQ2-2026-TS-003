package com.medconnect.care_queue_service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestContainers {

	/** Shared by every IT so they reuse one context; the relay never polls on its own during tests. */
	static final String NO_RELAY_POLLING = "medconnect.messaging.outbox-poll-interval=1h";

	@Bean
	@ServiceConnection
	RabbitMQContainer rabbit() {
		return new RabbitMQContainer("rabbitmq:4-management-alpine");
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redis() {
		return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	}

	static Message envelope(String type, UUID aggregateId, long aggregateVersion, String payloadJson) {
		return envelope(type, UUID.randomUUID(), aggregateId, aggregateVersion, payloadJson);
	}

	static Message envelope(String type, UUID eventId, UUID aggregateId, long aggregateVersion, String payloadJson) {
		String json = """
				{"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s","producer":"test","aggregateId":"%s",
				 "aggregateVersion":%d,"correlationId":"%s","causationId":null,"actorId":null,"payload":%s}"""
			.formatted(eventId, type, Instant.now(), aggregateId, aggregateVersion, UUID.randomUUID(), payloadJson);
		return MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build();
	}

	static Message triaged(UUID cid, String priority, int triageVersion, Instant requestedAt) {
		return envelope("PatientTriaged", cid, triageVersion, """
				{"consultationId":"%s","patientId":"%s","priority":"%s","ruleId":"R1","ruleSetVersion":"v1",
				 "triageVersion":%d,"reason":"%s","requestedAt":"%s","triagedAt":"%s",
				 "symptoms":{"mainSymptom":"CHEST_PAIN","painLevel":9}}"""
			.formatted(cid, UUID.randomUUID(), priority, triageVersion, triageVersion == 1 ? "INITIAL" : "SYMPTOMS_UPDATED",
					requestedAt, Instant.now()));
	}

	static Message overridden(UUID cid, String previous, String priority, int triageVersion, Instant requestedAt) {
		return envelope("PriorityOverridden", cid, triageVersion, """
				{"consultationId":"%s","patientId":"%s","previousPriority":"%s","newPriority":"%s","triageVersion":%d,
				 "overriddenBy":"%s","reason":"worse","requestedAt":"%s","overriddenAt":"%s"}"""
			.formatted(cid, UUID.randomUUID(), previous, priority, triageVersion, UUID.randomUUID(), requestedAt, Instant.now()));
	}

	static Message cancelled(UUID cid) {
		return envelope("ConsultationCancelled", cid, 9, """
				{"consultationId":"%s","patientId":"%s","doctorId":null,"statusAtCancellation":"WAITING","reason":null,
				 "cancelledAt":"%s"}""".formatted(cid, UUID.randomUUID(), Instant.now()));
	}
}
