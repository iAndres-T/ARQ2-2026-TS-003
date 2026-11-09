package com.medconnect.pharmacy_provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Retry delays are shortened; initial delivery + 5 retries (6 attempts) then the DLX, per the project ruling. */
@SpringBootTest(properties = "medconnect.messaging.retry-delays=100ms,100ms,100ms,100ms,100ms")
@Import(PrescriptionListenerIT.Broker.class)
@ExtendWith(OutputCaptureExtension.class)
class PrescriptionListenerIT {

	@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
	static class Broker {

		@Bean
		@ServiceConnection
		RabbitMQContainer rabbit() {
			return new RabbitMQContainer("rabbitmq:4-management-alpine");
		}
	}

	@Autowired RabbitTemplate rabbit;
	@Autowired WebApplicationContext ctx;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
	}

	@AfterEach
	void outageOff() throws Exception {
		outage(false);
	}

	void outage(boolean enabled) throws Exception {
		mvc.perform(post("/admin/outage").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"))
			.andReturn();
	}

	static String envelope(UUID eventId, UUID prescriptionId) {
		return """
				{"eventId":"%s","eventType":"PrescriptionIssued","eventVersion":1,"occurredAt":"2026-12-15T10:45:00Z",
				 "producer":"prescription-service","aggregateId":"%s","aggregateVersion":1,"correlationId":"%s","causationId":null,
				 "actorId":null,"payload":{"prescriptionId":"%s","consultationId":"%s","patientId":"%s","doctorId":"%s",
				 "items":[{"medicationName":"SecretMedication","dosage":"100 mg","frequency":"once daily","durationDays":30,"instructions":null}],
				 "notes":null,"issuedAt":"2026-12-15T10:45:00Z"}}""".formatted(eventId, prescriptionId, UUID.randomUUID(),
				prescriptionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
	}

	void publish(String json) {
		rabbit.send("medconnect.pharmacy", "prescription.issued",
				MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build());
	}

	void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("condition not met within 30 s");
			}
			Thread.sleep(50);
		}
	}

	Message receiveFrom(String queue, String containing) {
		for (int i = 0; i < 40; i++) {
			Message m = rabbit.receive(queue, 500);
			if (m != null && new String(m.getBody()).contains(containing)) {
				return m;
			}
		}
		return null;
	}

	@Test // S-F5-05
	void duplicateEventIdLoggedOnce(CapturedOutput output) throws Exception {
		UUID eventId = UUID.randomUUID();
		UUID prescriptionId = UUID.randomUUID();
		publish(envelope(eventId, prescriptionId));
		publish(envelope(eventId, prescriptionId)); // broker redelivery: same eventId
		UUID sentinel = UUID.randomUUID();
		publish(envelope(sentinel, UUID.randomUUID()));
		await(() -> output.getAll().contains("Prescription received eventId=" + sentinel));

		String received = "Prescription received eventId=" + eventId + " prescriptionId=" + prescriptionId;
		assertThat(output.getAll().split(received, -1).length - 1).isEqualTo(1);
		assertThat(output.getAll()).doesNotContain("SecretMedication");
	}

	@Test
	void invalidPayloadDeadLettersToDlqAndDeliveryFailed(CapturedOutput output) throws Exception {
		UUID eventId = UUID.randomUUID();
		publish(envelope(eventId, UUID.randomUUID()).replace("\"items\"", "\"itemz\""));

		assertThat(receiveFrom("pharmacy.prescriptions.dlq", eventId.toString())).as("operator DLQ").isNotNull();
		assertThat(receiveFrom("prescription.delivery-failed", eventId.toString())).as("delivery-failed").isNotNull();
		assertThat(output.getAll()).doesNotContain("Prescription received eventId=" + eventId);
	}

	@Test
	void outageToggleMakesDeliveriesFailUntilDisabled(CapturedOutput output) throws Exception {
		outage(true);
		UUID down = UUID.randomUUID();
		publish(envelope(down, UUID.randomUUID()));
		assertThat(receiveFrom("pharmacy.prescriptions.dlq", down.toString())).isNotNull();

		outage(false);
		UUID up = UUID.randomUUID();
		publish(envelope(up, UUID.randomUUID()));
		await(() -> output.getAll().contains("Prescription received eventId=" + up));
		assertThat(output.getAll()).doesNotContain("Prescription received eventId=" + down);
	}
}
