package com.medconnect.scheduling_service;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared MockMvc, JWT and envelope helpers for the integration tests. */
abstract class ApiSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	WebApplicationContext ctx;
	@Autowired
	JdbcTemplate jdbc;
	MockMvc mvc;

	@BeforeEach
	void mockMvc() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	static RequestPostProcessor doctor(UUID doctorId, String... scopes) {
		return jwt().jwt(j -> {
			j.subject(UUID.randomUUID().toString()).claim("role", "MEDICAL_STAFF");
			if (doctorId != null) {
				j.claim("doctorId", doctorId.toString());
			}
		}).authorities(Stream.concat(Stream.of("ROLE_MEDICAL_STAFF"), Arrays.stream(scopes).map(s -> "SCOPE_" + s))
			.<org.springframework.security.core.GrantedAuthority>map(SimpleGrantedAuthority::new).toList());
	}

	static RequestPostProcessor admin() {
		return doctor(null, "scheduling:manage");
	}

	MockHttpServletResponse createShift(UUID doctorId, Instant from, Instant to, RequestPostProcessor who) throws Exception {
		return mvc.perform(post("/api/v1/shifts").with(who).contentType(MediaType.APPLICATION_JSON)
			.content("{\"doctorId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(doctorId, from, to)))
			.andReturn().getResponse();
	}

	/** A shift covering now, which is what makes FREE legal. */
	void activeShift(UUID doctorId) throws Exception {
		Instant now = Instant.now();
		MockHttpServletResponse r = createShift(doctorId, now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), admin());
		if (r.getStatus() != 201) {
			throw new AssertionError("shift not created: " + r.getStatus() + " " + r.getContentAsString());
		}
	}

	MockHttpServletResponse setStatus(UUID doctorId, String status) throws Exception {
		return mvc.perform(put("/api/v1/doctors/me/availability").with(doctor(doctorId))
			.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"%s\"}".formatted(status))).andReturn().getResponse();
	}

	JsonNode availability(UUID doctorId) throws Exception {
		MockHttpServletResponse r = mvc.perform(get("/api/v1/doctors/availability").param("limit", "200").with(doctor(doctorId)))
			.andReturn().getResponse();
		for (JsonNode n : JSON.readTree(r.getContentAsString()).get("items")) {
			if (n.get("doctorId").asString().equals(doctorId.toString())) {
				return n;
			}
		}
		return null;
	}

	String status(UUID doctorId) throws Exception {
		return availability(doctorId).get("status").asString();
	}

	static Message msg(String json) {
		return MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build();
	}

	static String envelope(String type, UUID consultationId, UUID doctorId, String extra) {
		return """
				{"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s","producer":"test","aggregateId":"%s",
				 "aggregateVersion":1,"correlationId":"%s","causationId":null,"actorId":null,
				 "payload":{"consultationId":"%s","patientId":"%s","doctorId":%s%s}}""".formatted(UUID.randomUUID(), type,
				Instant.now(), consultationId, UUID.randomUUID(), consultationId, UUID.randomUUID(),
				doctorId == null ? "null" : "\"" + doctorId + "\"", extra);
	}

	List<JsonNode> outbox(UUID doctorId, String eventType) {
		return jdbc.queryForList("select envelope::text from outbox where envelope->>'aggregateId' = ? and envelope->>'eventType' = ? order by (envelope->>'aggregateVersion')::int",
				String.class, doctorId.toString(), eventType).stream().map(JSON::readTree).toList();
	}
}
