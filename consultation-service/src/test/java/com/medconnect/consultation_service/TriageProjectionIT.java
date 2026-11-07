package com.medconnect.consultation_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.medconnect.consultation_service.events.QueueEventsListener;
import com.medconnect.consultation_service.events.TriageEventsListener;

@SpringBootTest
@Import(TestContainers.class)
class TriageProjectionIT {

	@Autowired
	TriageEventsListener triage;
	@Autowired
	QueueEventsListener queue;
	@Autowired
	JdbcTemplate jdbc;

	UUID seed(String status) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into consultations(id, patient_id, status, symptoms, requested_at, version) values (?, ?, ?, '{}', now(), 1)",
				id, UUID.randomUUID(), status);
		return id;
	}

	Message envelope(String type, UUID eventId, UUID consultationId, String payloadFields) {
		String json = """
				{"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s","producer":"x","aggregateId":"%s",
				 "aggregateVersion":1,"correlationId":"%s","causationId":null,"actorId":null,
				 "payload":{"consultationId":"%s",%s}}""".formatted(eventId, type, Instant.now(), consultationId,
				UUID.randomUUID(), consultationId, payloadFields);
		return MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build();
	}

	Message triaged(UUID id, String priority, int triageVersion) {
		return envelope("PatientTriaged", UUID.randomUUID(), id, "\"priority\":\"%s\",\"triageVersion\":%d".formatted(priority, triageVersion));
	}

	Message enqueued(UUID id, String q, int triageVersion) {
		return envelope("PatientEnqueued", UUID.randomUUID(), id, "\"queue\":\"%s\",\"triageVersion\":%d".formatted(q, triageVersion));
	}

	String status(UUID id) {
		return jdbc.queryForObject("select status from consultations where id = ?", String.class, id);
	}

	String priority(UUID id) {
		return jdbc.queryForObject("select current_priority from consultations where id = ?", String.class, id);
	}

	long version(UUID id) {
		return jdbc.queryForObject("select version from consultations where id = ?", Long.class, id);
	}

	@Test
	void triagedMovesRequestedToTriaged() {
		UUID id = seed("REQUESTED");
		triage.onPatientTriaged(triaged(id, "HIGH", 1));
		assertThat(status(id)).isEqualTo("TRIAGED");
		assertThat(priority(id)).isEqualTo("HIGH");
		assertThat(version(id)).isEqualTo(2);
	}

	// Review Focus 1
	@Test
	void forwardOnlyTransitions() {
		UUID id = seed("REQUESTED");
		queue.onPatientEnqueued(enqueued(id, "HIGH", 1));
		triage.onPatientTriaged(triaged(id, "HIGH", 1));
		assertThat(status(id)).isEqualTo("WAITING");
	}

	@Test
	void staleTriageVersionIgnored() {
		UUID id = seed("REQUESTED");
		triage.onPatientTriaged(triaged(id, "HIGH", 3));
		triage.onPatientTriaged(triaged(id, "LOW", 2));
		assertThat(priority(id)).isEqualTo("HIGH");
	}

	@Test
	void overrideUpdatesPriorityOnlyIfNewerAndKeepsStatus() {
		UUID id = seed("WAITING");
		triage.onPriorityOverridden(envelope("PriorityOverridden", UUID.randomUUID(), id, "\"newPriority\":\"HIGH\",\"triageVersion\":2"));
		assertThat(priority(id)).isEqualTo("HIGH");
		triage.onPriorityOverridden(envelope("PriorityOverridden", UUID.randomUUID(), id, "\"newPriority\":\"LOW\",\"triageVersion\":1"));
		assertThat(priority(id)).isEqualTo("HIGH");
		assertThat(status(id)).isEqualTo("WAITING");
	}

	@Test
	void enqueuedSetsWaitingAndPriorityWhenNotStale() {
		UUID id = seed("TRIAGED");
		triage.onPatientTriaged(triaged(id, "MEDIUM", 2));
		queue.onPatientEnqueued(enqueued(id, "HIGH", 2)); // aged up
		assertThat(status(id)).isEqualTo("WAITING");
		assertThat(priority(id)).isEqualTo("HIGH");
		queue.onPatientEnqueued(enqueued(id, "LOW", 1)); // stale
		assertThat(priority(id)).isEqualTo("HIGH");
	}

	@Test
	void duplicateEventIsAppliedOnce() {
		UUID id = seed("REQUESTED");
		Message m = envelope("PatientTriaged", UUID.randomUUID(), id, "\"priority\":\"LOW\",\"triageVersion\":1");
		triage.onPatientTriaged(m);
		triage.onPatientTriaged(m);
		assertThat(version(id)).isEqualTo(2);
	}

	@Test
	void terminalConsultationsAreNeverTouched() {
		UUID id = seed("CANCELLED");
		triage.onPatientTriaged(triaged(id, "HIGH", 1));
		queue.onPatientEnqueued(enqueued(id, "HIGH", 1));
		assertThat(status(id)).isEqualTo("CANCELLED");
		assertThat(priority(id)).isNull();
		assertThat(version(id)).isEqualTo(1);
	}
}
