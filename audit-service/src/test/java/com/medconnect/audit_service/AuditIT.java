package com.medconnect.audit_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestContainers.class)
class AuditIT {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String EVENTS = "medconnect.events";
	static final String PHARMACY = "medconnect.pharmacy";

	@Autowired
	WebApplicationContext ctx;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	RabbitTemplate rabbit;
	@Autowired
	PostgreSQLContainer pg;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	static RequestPostProcessor auditor() {
		return staff("SCOPE_audit:read");
	}

	static RequestPostProcessor staff(String... extra) {
		List<GrantedAuthority> auth = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_MEDICAL_STAFF")));
		for (String e : extra) {
			auth.add(new SimpleGrantedAuthority(e));
		}
		return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "MEDICAL_STAFF")).authorities(auth);
	}

	static String envelope(UUID eventId, String type, UUID aggregateId, UUID actorId, Instant at, String payload) {
		return """
				{"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s","producer":"test","aggregateId":"%s",
				 "aggregateVersion":1,"correlationId":"%s","causationId":null,"actorId":%s,"payload":%s}"""
			.formatted(eventId, type, at, aggregateId, UUID.randomUUID(), actorId == null ? "null" : "\"" + actorId + "\"", payload);
	}

	void publish(String exchange, String routingKey, String json) {
		Message m = MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build();
		rabbit.send(exchange, routingKey, m);
	}

	void awaitStored(UUID eventId) {
		await().atMost(Duration.ofSeconds(20)).until(() -> count(eventId) == 1);
	}

	int count(UUID eventId) {
		return jdbc.queryForObject("select count(*) from audit_event where event_id = ?", Integer.class, eventId);
	}

	JsonNode query(String qs) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/v1/audit/events" + qs).with(auditor())).andReturn().getResponse();
		assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
		return JSON.readTree(r.getContentAsString());
	}

	@Test
	void storesEveryEventOnceByEventId() {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		String ea = envelope(a, "PatientAssigned", UUID.randomUUID(), null, Instant.now(), "{\"x\":1}");
		publish(EVENTS, "queue.patient-assigned", ea);
		publish(EVENTS, "queue.patient-assigned", ea); // duplicate delivery
		publish(PHARMACY, "prescription.issued", envelope(b, "PrescriptionIssued", UUID.randomUUID(), null, Instant.now(), "{}"));
		awaitStored(a);
		awaitStored(b);
		// a later marker event proves the duplicate was consumed (acked) before we assert
		UUID marker = UUID.randomUUID();
		publish(EVENTS, "consultation.completed", envelope(marker, "ConsultationCompleted", UUID.randomUUID(), null, Instant.now(), "{}"));
		awaitStored(marker);
		assertThat(count(a)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select producer from audit_event where event_id = ?", String.class, a)).isEqualTo("test");
	}

	@Test
	void updateAndDeleteAreRejectedByDatabase() {
		UUID id = UUID.randomUUID();
		publish(EVENTS, "consultation.completed", envelope(id, "ConsultationCompleted", UUID.randomUUID(), null, Instant.now(), "{}"));
		awaitStored(id);
		// restricted runtime user: no UPDATE/DELETE/TRUNCATE privilege
		assertThatThrownBy(() -> jdbc.update("update audit_event set event_type = 'X' where event_id = ?", id)).rootCause().hasMessageContaining("permission denied");
		assertThatThrownBy(() -> jdbc.update("delete from audit_event where event_id = ?", id)).rootCause().hasMessageContaining("permission denied");
		// even the owner is stopped by the trigger
		JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
		assertThatThrownBy(() -> owner.update("update audit_event set event_type = 'X' where event_id = ?", id)).rootCause().hasMessageContaining("append-only");
		assertThatThrownBy(() -> owner.update("delete from audit_event where event_id = ?", id)).rootCause().hasMessageContaining("append-only");
		assertThatThrownBy(() -> owner.execute("truncate audit_event")).rootCause().hasMessageContaining("append-only");
		assertThat(count(id)).isEqualTo(1);
	}

	@Test
	void filterByEventTypePriorityOverridden() throws Exception { // S-F3-07
		UUID consultation = UUID.randomUUID(), actor = UUID.randomUUID(), id = UUID.randomUUID();
		publish(EVENTS, "triage.priority-overridden", envelope(id, "PriorityOverridden", consultation, actor, Instant.now(),
				"{\"previousPriority\":\"MEDIUM\",\"newPriority\":\"HIGH\",\"reason\":\"Radiating pain on call-back\"}"));
		awaitStored(id);
		JsonNode body = query("?eventType=PriorityOverridden&aggregateId=" + consultation + "&actorId=" + actor);
		assertThat(body.get("items")).hasSize(1);
		JsonNode item = body.get("items").get(0);
		assertThat(item.get("eventId").asString()).isEqualTo(id.toString());
		assertThat(item.get("actorId").asString()).isEqualTo(actor.toString());
		assertThat(item.get("payload").get("reason").asString()).isEqualTo("Radiating pain on call-back");
		assertThat(query("?eventType=NoSuchEvent&aggregateId=" + consultation).get("items")).isEmpty();
	}

	@Test
	void symptomFreeTextAndClinicalSummaryAreNotStored() throws Exception {
		UUID agg = UUID.randomUUID(), id = UUID.randomUUID();
		publish(EVENTS, "triage.patient-triaged", envelope(id, "PatientTriaged", agg, null, Instant.now(),
				"{\"priority\":\"HIGH\",\"clinicalSummary\":\"secret\",\"symptoms\":{\"mainSymptom\":\"CHEST_PAIN\",\"painLevel\":9,\"description\":\"private text\"}}"));
		awaitStored(id);
		JsonNode payload = query("?aggregateId=" + agg).get("items").get(0).get("payload");
		assertThat(payload.has("clinicalSummary")).isFalse();
		assertThat(payload.get("symptoms").has("description")).isFalse();
		assertThat(payload.get("symptoms").get("mainSymptom").asString()).isEqualTo("CHEST_PAIN");
		assertThat(payload.get("priority").asString()).isEqualTo("HIGH");
	}

	@Test
	void cursorPaginationIsStableAndOrdered() throws Exception {
		UUID agg = UUID.randomUUID();
		Instant t = Instant.parse("2026-01-01T00:00:00Z");
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			UUID id = UUID.randomUUID();
			ids.add(id);
			publish(EVENTS, "consultation.completed", envelope(id, "ConsultationCompleted", agg, null, t.plusSeconds(i % 3), "{}"));
		}
		await().atMost(Duration.ofSeconds(20)).until(() -> ids.stream().allMatch(i -> count(i) == 1));
		List<String> seen = new ArrayList<>();
		String cursor = null;
		do {
			JsonNode page = query("?aggregateId=" + agg + "&limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
			page.get("items").forEach(n -> seen.add(n.get("eventId").asString()));
			cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
		} while (cursor != null);
		assertThat(seen).hasSize(5).doesNotHaveDuplicates();
		List<String> expected = jdbc.queryForList("select event_id::text from audit_event where aggregate_id = ? order by occurred_at, event_id", String.class, agg);
		assertThat(seen).isEqualTo(expected);
		assertThat(query("?aggregateId=" + agg + "&from=2026-01-01T00:00:01Z&to=2026-01-01T00:00:01Z").get("items")).hasSize(2);
	}

	@Test
	void missingAuditScopeIs403() throws Exception {
		assertThat(mvc.perform(get("/api/v1/audit/events").with(staff())).andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(mvc.perform(get("/api/v1/audit/events")).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void auditRecordsCannotBeModifiedOverHttp() throws Exception {
		assertThat(mvc.perform(delete("/api/v1/audit/events").with(auditor())).andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
		assertThat(mvc.perform(post("/api/v1/audit/events").with(auditor())).andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
	}

	@Test
	void invalidFromDateIs400() throws Exception {
		for (String qs : new String[] { "?from=yesterday", "?to=nope", "?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z",
				"?limit=0", "?limit=201", "?limit=abc", "?cursor=!!!", "?aggregateId=not-a-uuid" }) {
			MockHttpServletResponse r = mvc.perform(get("/api/v1/audit/events" + qs).with(auditor())).andReturn().getResponse();
			assertThat(r.getStatus()).as(qs).isEqualTo(400);
		}
	}
}
