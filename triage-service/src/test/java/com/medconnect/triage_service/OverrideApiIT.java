package com.medconnect.triage_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.medconnect.triage_service.events.ConsultationEventsListener;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestContainers.class)
class OverrideApiIT {

	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	WebApplicationContext ctx;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ConsultationEventsListener listener;
	MockMvc mvc;
	UUID staffId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	RequestPostProcessor staff() {
		return jwt().jwt(j -> j.subject(staffId.toString()).claim("role", "MEDICAL_STAFF").claim("doctorId", UUID.randomUUID().toString()))
			.authorities(new SimpleGrantedAuthority("ROLE_MEDICAL_STAFF"));
	}

	static RequestPostProcessor patient(UUID id) {
		return jwt().jwt(j -> j.subject(id.toString()).claim("role", "PATIENT")).authorities(new SimpleGrantedAuthority("ROLE_PATIENT"));
	}

	static Message msg(String json) {
		return MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build();
	}

	/** Triages a FEVER/8 request: MEDIUM. */
	UUID triaged(UUID patient) {
		UUID id = UUID.randomUUID();
		listener.onConsultationRequested(msg(Events.requested(UUID.randomUUID(), id, patient, Events.SYMPTOMS_FEVER)));
		return id;
	}

	MockHttpServletResponse override(UUID id, String body, RequestPostProcessor who) throws Exception {
		return mvc.perform(post("/api/v1/triage/consultations/" + id + "/override").with(who)
			.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
	}

	MockHttpServletResponse view(UUID id, RequestPostProcessor who) throws Exception {
		return mvc.perform(get("/api/v1/triage/consultations/" + id).with(who)).andReturn().getResponse();
	}

	static String body(String priority, String reason) {
		return "{\"newPriority\":\"%s\",\"reason\":\"%s\"}".formatted(priority, reason);
	}

	@Test // S-F3-07
	void overrideEmitsPriorityOverridden() throws Exception {
		UUID id = triaged(UUID.randomUUID());
		MockHttpServletResponse r = override(id, body("HIGH", "Patient reports radiating pain"), staff());
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode resp = JSON.readTree(r.getContentAsString());
		assertThat(resp.get("previousPriority").asString()).isEqualTo("MEDIUM");
		assertThat(resp.get("newPriority").asString()).isEqualTo("HIGH");
		assertThat(resp.get("source").asString()).isEqualTo("OVERRIDE");
		assertThat(resp.get("triageVersion").asInt()).isEqualTo(2);
		assertThat(resp.get("overriddenBy").asString()).isEqualTo(staffId.toString());

		JsonNode e = JSON.readTree(jdbc.queryForObject("select envelope::text from outbox where envelope->>'aggregateId' = ? and envelope->>'eventType' = 'PriorityOverridden'",
				String.class, id.toString()));
		assertThat(e.get("aggregateVersion").asInt()).isEqualTo(2);
		assertThat(e.get("actorId").asString()).isEqualTo(staffId.toString());
		JsonNode p = e.get("payload");
		assertThat(p.get("previousPriority").asString()).isEqualTo("MEDIUM");
		assertThat(p.get("newPriority").asString()).isEqualTo("HIGH");
		assertThat(p.get("overriddenBy").asString()).isEqualTo(staffId.toString());
		assertThat(p.get("reason").asString()).isEqualTo("Patient reports radiating pain");
		assertThat(p.get("triageVersion").asInt()).isEqualTo(2);
		assertThat(p.get("requestedAt").asString()).isEqualTo("2026-12-15T10:15:30Z");
		assertThat(p.get("overriddenAt").asString()).isNotBlank();

		JsonNode v = JSON.readTree(view(id, staff()).getContentAsString());
		assertThat(v.get("source").asString()).isEqualTo("OVERRIDE");
		assertThat(v.get("reason").asString()).isEqualTo("Patient reports radiating pain");
	}

	@Test
	void overrideSamePriorityIs409() throws Exception {
		UUID id = triaged(UUID.randomUUID());
		assertThat(override(id, body("MEDIUM", "Same as current"), staff()).getStatus()).isEqualTo(409);
	}

	@Test // S-F3-09 via the D7 status projection
	void overrideAfterAssignedIs409() throws Exception {
		UUID id = triaged(UUID.randomUUID());
		listener.onPatientAssigned(msg(Events.envelope("PatientAssigned", UUID.randomUUID(), id, 1,
				"\"patientId\":\"%s\",\"doctorId\":\"%s\",\"priority\":\"MEDIUM\",\"assignedAt\":\"2026-12-15T10:20:00Z\"".formatted(UUID.randomUUID(), UUID.randomUUID()))));
		assertThat(override(id, body("HIGH", "Too late now"), staff()).getStatus()).isEqualTo(409);
	}

	@Test
	void overrideAfterCancelledIs409() throws Exception {
		UUID id = triaged(UUID.randomUUID());
		listener.onConsultationCancelled(msg(Events.envelope("ConsultationCancelled", UUID.randomUUID(), id, 3,
				"\"patientId\":\"%s\",\"doctorId\":null,\"statusAtCancellation\":\"TRIAGED\",\"reason\":null,\"cancelledAt\":\"2026-12-15T10:20:00Z\"".formatted(UUID.randomUUID()))));
		assertThat(override(id, body("HIGH", "Too late now"), staff()).getStatus()).isEqualTo(409);
	}

	@Test
	void unknownConsultationIs404AndPatientIs403() throws Exception {
		assertThat(override(UUID.randomUUID(), body("HIGH", "No such thing"), staff()).getStatus()).isEqualTo(404);
		UUID id = triaged(UUID.randomUUID());
		assertThat(override(id, body("HIGH", "Patients cannot"), patient(UUID.randomUUID())).getStatus()).isEqualTo(403); // S-F1-04
	}

	@Test
	void reasonShorterThan5Is422() throws Exception {
		UUID id = triaged(UUID.randomUUID());
		assertThat(override(id, body("HIGH", "abcd"), staff()).getStatus()).isEqualTo(422);
		assertThat(override(id, body("HIGH", "x".repeat(501)), staff()).getStatus()).isEqualTo(422);
		assertThat(override(id, "{\"newPriority\":\"HIGH\"}", staff()).getStatus()).isEqualTo(422);
	}

	@Test
	void patientViewOmitsRuleId() throws Exception {
		UUID patient = UUID.randomUUID();
		UUID id = triaged(patient);
		JsonNode own = JSON.readTree(view(id, patient(patient)).getContentAsString());
		assertThat(own.get("priority").asString()).isEqualTo("MEDIUM");
		assertThat(own.has("ruleId")).isFalse();
		assertThat(own.has("reason")).isFalse();
		assertThat(own.has("overriddenBy")).isFalse();
		assertThat(own.get("ruleSetVersion").asString()).isEqualTo("v1");

		JsonNode doc = JSON.readTree(view(id, staff()).getContentAsString());
		assertThat(doc.get("ruleId").asString()).isEqualTo("R3_FEVER_HIGH_PAIN");
		assertThat(doc.get("overriddenBy").isNull()).isTrue();

		assertThat(view(id, patient(UUID.randomUUID())).getStatus()).isEqualTo(403);
		assertThat(view(UUID.randomUUID(), staff()).getStatus()).isEqualTo(404);
	}
}
