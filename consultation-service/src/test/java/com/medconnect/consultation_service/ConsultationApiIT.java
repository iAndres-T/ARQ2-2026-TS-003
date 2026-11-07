package com.medconnect.consultation_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
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

@SpringBootTest
@Import(TestContainers.class)
class ConsultationApiIT {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String REPORT = """
			{"mainSymptom":"CHEST_PAIN","painLevel":9,"symptomDuration":{"value":30,"unit":"MINUTES"},"age":58,
			 "redFlags":{"difficultyBreathing":false,"lossOfConsciousness":false},"description":"Pressure"}""";

	@Autowired
	WebApplicationContext ctx;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	CacheManager cacheManager;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	static RequestPostProcessor patient(UUID id) {
		return jwt().jwt(j -> j.subject(id.toString()).claim("role", "PATIENT")).authorities(new SimpleGrantedAuthority("ROLE_PATIENT"));
	}

	static RequestPostProcessor doctor(UUID doctorId) {
		return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "MEDICAL_STAFF").claim("doctorId", doctorId.toString()))
			.authorities(new SimpleGrantedAuthority("ROLE_MEDICAL_STAFF"));
	}

	MockHttpServletResponse create(UUID patientId, String body) throws Exception {
		return mvc.perform(post("/api/v1/consultations").with(patient(patientId))
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andReturn().getResponse();
	}

	UUID createOk(UUID patientId) throws Exception {
		MockHttpServletResponse r = create(patientId, REPORT);
		assertThat(r.getStatus()).isEqualTo(202);
		return UUID.fromString(JSON.readTree(r.getContentAsString()).get("consultationId").asString());
	}

	MockHttpServletResponse getAs(RequestPostProcessor user, UUID id) throws Exception {
		return mvc.perform(get("/api/v1/consultations/" + id).with(user)).andReturn().getResponse();
	}

	MockHttpServletResponse cancel(UUID patientId, UUID id, String body) throws Exception {
		var req = post("/api/v1/consultations/" + id + "/cancel").with(patient(patientId));
		if (body != null) {
			req.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(req).andReturn().getResponse();
	}

	MockHttpServletResponse putSymptoms(UUID patientId, UUID id, String body) throws Exception {
		return mvc.perform(put("/api/v1/consultations/" + id + "/symptoms").with(patient(patientId))
			.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
	}

	void setStatus(UUID id, String status) {
		jdbc.update("update consultations set status = ? where id = ?", status, id);
	}

	@Test
	void postReturns202AndWritesOutbox() throws Exception {
		UUID patientId = UUID.randomUUID();
		MockHttpServletResponse r = create(patientId, REPORT);
		assertThat(r.getStatus()).isEqualTo(202);
		JsonNode body = JSON.readTree(r.getContentAsString());
		assertThat(body.get("status").asString()).isEqualTo("REQUESTED");
		assertThat(body.get("requestedAt").asString()).isNotBlank();
		List<String> envelopes = jdbc.queryForList(
				"select envelope::text from outbox where routing_key = 'consultation.requested' and envelope->'payload'->>'patientId' = ?",
				String.class, patientId.toString());
		assertThat(envelopes).hasSize(1);
		JsonNode env = JSON.readTree(envelopes.get(0));
		assertThat(env.get("eventType").asString()).isEqualTo("ConsultationRequested");
		assertThat(env.get("aggregateVersion").asInt()).isEqualTo(1);
		assertThat(env.get("payload").get("consultationId").asString()).isEqualTo(body.get("consultationId").asString());
	}

	@Test
	void missingIdempotencyKeyIs400() throws Exception {
		var r = mvc.perform(post("/api/v1/consultations").with(patient(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON).content(REPORT))
			.andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(400);
	}

	@Test
	void sameIdempotencyKeyReplaysSameConsultation() throws Exception {
		UUID p = UUID.randomUUID();
		String key = UUID.randomUUID().toString();
		String first = mvc.perform(post("/api/v1/consultations").with(patient(p)).header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON).content(REPORT)).andReturn().getResponse().getContentAsString();
		var second = mvc.perform(post("/api/v1/consultations").with(patient(p)).header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON).content(REPORT)).andReturn().getResponse();
		assertThat(second.getStatus()).isEqualTo(202);
		assertThat(JSON.readTree(second.getContentAsString()).get("consultationId"))
			.isEqualTo(JSON.readTree(first).get("consultationId"));
	}

	// S-F3-05
	@Test
	void secondActiveConsultationIs409() throws Exception {
		UUID p = UUID.randomUUID();
		createOk(p);
		assertThat(create(p, REPORT).getStatus()).isEqualTo(409);
	}

	@Test
	void concurrentRequestsCreateOnlyOne() throws Exception {
		UUID p = UUID.randomUUID();
		ExecutorService pool = Executors.newFixedThreadPool(4);
		List<Callable<Integer>> calls = IntStream.range(0, 4).<Callable<Integer>>mapToObj(i -> () -> create(p, REPORT).getStatus()).toList();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> f : pool.invokeAll(calls)) {
			statuses.add(f.get());
		}
		pool.shutdown();
		assertThat(statuses).containsOnly(202, 409);
		assertThat(statuses.stream().filter(s -> s == 202).count()).isEqualTo(1);
	}

	@Test
	void painLevel11Is422() throws Exception {
		assertThat(create(UUID.randomUUID(), REPORT.replace("\"painLevel\":9", "\"painLevel\":11")).getStatus()).isEqualTo(422);
	}

	@Test
	void doctorCannotCreate() throws Exception {
		var r = mvc.perform(post("/api/v1/consultations").with(doctor(UUID.randomUUID())).header("Idempotency-Key", "k1")
			.contentType(MediaType.APPLICATION_JSON).content(REPORT)).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(403);
	}

	// S-F1-06
	@Test
	void otherPatientGetsIs403() throws Exception {
		UUID owner = UUID.randomUUID();
		UUID id = createOk(owner);
		assertThat(getAs(patient(owner), id).getStatus()).isEqualTo(200);
		assertThat(getAs(patient(UUID.randomUUID()), id).getStatus()).isEqualTo(403);
		// the cached copy must not leak either
		assertThat(getAs(patient(UUID.randomUUID()), id).getStatus()).isEqualTo(403);
	}

	@Test
	void unknownConsultationIs404() throws Exception {
		assertThat(getAs(patient(UUID.randomUUID()), UUID.randomUUID()).getStatus()).isEqualTo(404);
	}

	@Test
	void staffReadsOnlyOwnAssignedConsultations() throws Exception {
		UUID doctorId = UUID.randomUUID();
		UUID id = createOk(UUID.randomUUID());
		assertThat(getAs(doctor(doctorId), id).getStatus()).isEqualTo(403);
		jdbc.update("update consultations set doctor_id = ?, status = 'ASSIGNED' where id = ?", doctorId, id);
		cacheManager.getCache("consultations").evict(id.toString()); // direct SQL bypasses the service-level eviction
		assertThat(getAs(doctor(doctorId), id).getStatus()).isEqualTo(200);
		assertThat(getAs(doctor(UUID.randomUUID()), id).getStatus()).isEqualTo(403);
	}

	@Test
	void cachedReadIsEvictedOnStateChange() throws Exception {
		UUID p = UUID.randomUUID();
		UUID id = createOk(p);
		assertThat(JSON.readTree(getAs(patient(p), id).getContentAsString()).get("status").asString()).isEqualTo("REQUESTED");
		assertThat(cancel(p, id, null).getStatus()).isEqualTo(200);
		JsonNode after = JSON.readTree(getAs(patient(p), id).getContentAsString());
		assertThat(after.get("status").asString()).isEqualTo("CANCELLED");
		assertThat(after.get("version").asInt()).isEqualTo(2);
	}

	@Test
	void cancelFromInProgressIs409() throws Exception {
		UUID p = UUID.randomUUID();
		UUID id = createOk(p);
		setStatus(id, "IN_PROGRESS");
		assertThat(cancel(p, id, null).getStatus()).isEqualTo(409);
	}

	@Test
	void cancelWritesConsultationCancelledWithStatusAtCancellation() throws Exception {
		UUID p = UUID.randomUUID();
		UUID id = createOk(p);
		setStatus(id, "WAITING");
		MockHttpServletResponse r = cancel(p, id, "{\"reason\":\"Feeling better\"}");
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(r.getContentAsString()).get("status").asString()).isEqualTo("CANCELLED");
		String env = jdbc.queryForObject("select envelope::text from outbox where routing_key = 'consultation.cancelled' and envelope->>'aggregateId' = ?",
				String.class, id.toString());
		JsonNode payload = JSON.readTree(env).get("payload");
		assertThat(payload.get("statusAtCancellation").asString()).isEqualTo("WAITING");
		assertThat(payload.get("reason").asString()).isEqualTo("Feeling better");
		assertThat(payload.get("doctorId").isNull()).isTrue();
		assertThat(payload.get("patientId").asString()).isEqualTo(p.toString());
		assertThat(payload.get("cancelledAt").asString()).isNotBlank();
	}

	@Test
	void cancelByOtherPatientIs403() throws Exception {
		UUID id = createOk(UUID.randomUUID());
		assertThat(cancel(UUID.randomUUID(), id, null).getStatus()).isEqualTo(403);
	}

	@Test
	void updateSymptomsOnlyWhenTriagedOrWaiting() throws Exception {
		UUID p = UUID.randomUUID();
		UUID id = createOk(p);
		String updated = REPORT.replace("CHEST_PAIN", "SHORTNESS_OF_BREATH");
		assertThat(putSymptoms(p, id, updated).getStatus()).isEqualTo(409); // REQUESTED
		setStatus(id, "TRIAGED");
		MockHttpServletResponse r = putSymptoms(p, id, updated);
		assertThat(r.getStatus()).isEqualTo(202);
		assertThat(JSON.readTree(r.getContentAsString()).get("status").asString()).isEqualTo("TRIAGED");
		String env = jdbc.queryForObject("select envelope::text from outbox where routing_key = 'consultation.symptoms-updated' and envelope->>'aggregateId' = ?",
				String.class, id.toString());
		assertThat(JSON.readTree(env).get("payload").get("symptoms").get("mainSymptom").asString()).isEqualTo("SHORTNESS_OF_BREATH");
		assertThat(JSON.readTree(env).get("aggregateVersion").asInt()).isEqualTo(2);
	}
}
