package com.medconnect.triage_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.medconnect.triage_service.events.ConsultationEventsListener;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestContainers.class)
class TriageFlowIT {

	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	RabbitTemplate rabbit;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ConsultationEventsListener listener;

	static Message msg(String json) {
		return MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build();
	}

	List<JsonNode> outbox(UUID id, String eventType) {
		return jdbc.queryForList("select envelope::text from outbox where envelope->>'aggregateId' = ? and envelope->>'eventType' = ? order by (envelope->>'aggregateVersion')::int",
				String.class, id.toString(), eventType).stream().map(JSON::readTree).toList();
	}

	@Test // S-F3-01, through the real broker: exchange -> triage.consultation-requested -> listener -> outbox
	void requestedEmitsPatientTriagedVersion1() throws Exception {
		UUID id = UUID.randomUUID(), patient = UUID.randomUUID();
		rabbit.send("medconnect.events", "consultation.requested", msg(Events.requested(UUID.randomUUID(), id, patient, Events.SYMPTOMS_CHEST)));
		for (int i = 0; i < 400 && outbox(id, "PatientTriaged").isEmpty(); i++) {
			Thread.sleep(50);
		}
		JsonNode e = outbox(id, "PatientTriaged").get(0);
		assertThat(e.get("producer").asString()).isEqualTo("triage-service");
		assertThat(e.get("aggregateVersion").asInt()).isEqualTo(1);
		JsonNode p = e.get("payload");
		assertThat(p.get("priority").asString()).isEqualTo("HIGH");
		assertThat(p.get("ruleId").asString()).isEqualTo("R2_CRITICAL_SYMPTOM");
		assertThat(p.get("ruleSetVersion").asString()).isEqualTo("v1");
		assertThat(p.get("triageVersion").asInt()).isEqualTo(1);
		assertThat(p.get("reason").asString()).isEqualTo("INITIAL");
		assertThat(p.get("patientId").asString()).isEqualTo(patient.toString());
		assertThat(p.get("requestedAt").asString()).isEqualTo("2026-12-15T10:15:30Z");
		assertThat(p.get("symptoms").get("mainSymptom").asString()).isEqualTo("CHEST_PAIN");
		assertThat(p.get("triagedAt").asString()).isNotBlank();
	}

	@Test // S-F3-08
	void symptomsUpdateIncrementsTriageVersion() {
		UUID id = UUID.randomUUID(), patient = UUID.randomUUID();
		listener.onConsultationRequested(msg(Events.requested(UUID.randomUUID(), id, patient, Events.SYMPTOMS_COUGH)));
		listener.onSymptomsUpdated(msg(Events.symptomsUpdated(UUID.randomUUID(), id, patient, 2, Events.SYMPTOMS_FEVER)));
		// same priority again: still a new version
		listener.onSymptomsUpdated(msg(Events.symptomsUpdated(UUID.randomUUID(), id, patient, 3, Events.SYMPTOMS_FEVER)));
		// stale update (aggregateVersion not newer than the last applied) is ignored
		listener.onSymptomsUpdated(msg(Events.symptomsUpdated(UUID.randomUUID(), id, patient, 2, Events.SYMPTOMS_CHEST)));

		List<JsonNode> events = outbox(id, "PatientTriaged");
		assertThat(events).hasSize(3);
		assertThat(events.stream().map(e -> e.get("payload").get("triageVersion").asInt())).containsExactly(1, 2, 3);
		assertThat(events.stream().map(e -> e.get("aggregateVersion").asInt())).containsExactly(1, 2, 3);
		assertThat(events.get(1).get("payload").get("priority").asString()).isEqualTo("MEDIUM");
		assertThat(events.get(1).get("payload").get("reason").asString()).isEqualTo("SYMPTOMS_UPDATED");
		assertThat(events.get(1).get("payload").get("requestedAt").asString()).isEqualTo("2026-12-15T10:15:30Z");
		assertThat(events.get(2).get("payload").get("priority").asString()).isEqualTo("MEDIUM");
	}

	@Test
	void redeliveredEventProducesOneOutboxRow() {
		UUID id = UUID.randomUUID();
		Message m = msg(Events.requested(UUID.randomUUID(), id, UUID.randomUUID(), Events.SYMPTOMS_COUGH));
		listener.onConsultationRequested(m);
		listener.onConsultationRequested(m);
		assertThat(outbox(id, "PatientTriaged")).hasSize(1);
	}
}
