package com.medconnect.consultation_service;

import static com.medconnect.consultation_service.ConsultationApiIT.REPORT;
import static com.medconnect.consultation_service.ConsultationApiIT.doctor;
import static com.medconnect.consultation_service.ConsultationApiIT.patient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.medconnect.consultation_service.events.AssignmentListener;
import com.medconnect.consultation_service.jobs.NoShowJob;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = { "medconnect.consultation.no-show-timeout=1s", "medconnect.consultation.no-show-interval=1h",
		"medconnect.internal-token=test-internal-token" })
@Import(TestContainers.class)
class LifecycleIT {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final HttpServer PROVIDER;
	/** Statuses the stub answers to the next POST /sessions calls; 200 when empty. */
	static final Queue<Integer> FAILURES = new ConcurrentLinkedQueue<>();
	static volatile long DELAY_MS;
	static final AtomicInteger CREATE_CALLS = new AtomicInteger();
	static final Queue<String> ENDED = new ConcurrentLinkedQueue<>();

	static {
		try {
			PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		PROVIDER.createContext("/sessions", LifecycleIT::provider);
		PROVIDER.start();
	}

	static void provider(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		if (path.endsWith("/end")) {
			ENDED.add(path.split("/")[2]);
			ex.sendResponseHeaders(204, -1);
			ex.close();
			return;
		}
		CREATE_CALLS.incrementAndGet();
		try {
			Thread.sleep(DELAY_MS);
		}
		catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
		Integer status = FAILURES.poll();
		if (status != null) {
			ex.sendResponseHeaders(status, -1);
			ex.close();
			return;
		}
		String cid = JSON.readTree(ex.getRequestBody().readAllBytes()).get("consultationId").asString();
		String sid = "s-" + cid;
		byte[] body = """
				{"sessionId":"%s","joinUrls":{"patient":"http://localhost:8090/room/%s?t=pt","doctor":"http://localhost:8090/room/%s?t=dr"}}"""
			.formatted(sid, sid, sid).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		ex.sendResponseHeaders(200, body.length);
		ex.getResponseBody().write(body);
		ex.close();
	}

	@DynamicPropertySource
	static void providerUrl(DynamicPropertyRegistry registry) {
		registry.add("medconnect.video.url", () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort());
	}

	@Autowired
	WebApplicationContext ctx;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AssignmentListener assignments;
	@Autowired
	NoShowJob noShowJob;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
		FAILURES.clear();
		DELAY_MS = 0;
		CREATE_CALLS.set(0);
	}

	record Seeded(UUID id, UUID patientId) {
	}

	Seeded seedWaiting() {
		Seeded s = new Seeded(UUID.randomUUID(), UUID.randomUUID());
		jdbc.update("""
				insert into consultations(id, patient_id, status, current_priority, triage_version, symptoms, requested_at, version)
				values (?, ?, 'WAITING', 'HIGH', 1, ?, now(), 3)""", s.id(), s.patientId(), REPORT);
		return s;
	}

	static Message assigned(Seeded s, UUID doctorId) {
		return assigned(s, doctorId, Instant.now());
	}

	static Message assigned(Seeded s, UUID doctorId, Instant assignedAt) {
		String json = """
				{"eventId":"%s","eventType":"PatientAssigned","eventVersion":1,"occurredAt":"%s","producer":"care-queue-service",
				 "aggregateId":"%s","aggregateVersion":1,"correlationId":"%s","causationId":null,"actorId":null,
				 "payload":{"consultationId":"%s","patientId":"%s","doctorId":"%s","priority":"HIGH","assignedAt":"%s"}}"""
			.formatted(UUID.randomUUID(), Instant.now(), s.id(), UUID.randomUUID(), s.id(), s.patientId(), doctorId, assignedAt);
		return MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build();
	}

	Seeded assignedConsultation(UUID doctorId) {
		Seeded s = seedWaiting();
		assignments.onPatientAssigned(assigned(s, doctorId));
		return s;
	}

	String status(UUID id) {
		return jdbc.queryForObject("select status from consultations where id = ?", String.class, id);
	}

	MockHttpServletResponse postAs(RequestPostProcessor user, UUID id, String action, String body) throws Exception {
		var req = post("/api/v1/consultations/" + id + "/" + action).with(user);
		if (body != null) {
			req.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(req).andReturn().getResponse();
	}

	List<JsonNode> outbox(String routingKey, UUID id) {
		return jdbc.queryForList("select envelope::text from outbox where routing_key = ? and envelope->>'aggregateId' = ?",
				String.class, routingKey, id.toString()).stream().map(JSON::readTree).toList();
	}

	// S-F4-07 (I1: the session is created lazily on join/start, never by the PatientAssigned handler)
	@Test
	void patientAssignedSetsAssignedWithoutCallingProvider() {
		UUID doctorId = UUID.randomUUID();
		Seeded s = seedWaiting();
		Message m = assigned(s, doctorId);
		assignments.onPatientAssigned(m);

		var row = jdbc.queryForMap("select * from consultations where id = ?", s.id());
		assertThat(row.get("status")).isEqualTo("ASSIGNED");
		assertThat(row.get("doctor_id")).isEqualTo(doctorId);
		assertThat(row.get("assigned_at")).isNotNull();
		assertThat(row.get("assigned_committed_at")).isNotNull();
		assertThat(row.get("session_id")).isNull();
		assertThat(row.get("version")).isEqualTo(4L);
		assertThat(CREATE_CALLS.get()).isZero();

		assignments.onPatientAssigned(m); // redelivery
		assignments.onPatientAssigned(assigned(s, doctorId)); // duplicate with another eventId
		assertThat(CREATE_CALLS.get()).isZero();
		assertThat(jdbc.queryForObject("select version from consultations where id = ?", Long.class, s.id())).isEqualTo(4L);
	}

	@Test
	void providerDownDoesNotStrandAssignmentJoinRecovers() throws Exception {
		Seeded s = seedWaiting();
		FAILURES.addAll(List.of(500, 500, 500, 500, 500, 500));
		assignments.onPatientAssigned(assigned(s, UUID.randomUUID()));
		assertThat(status(s.id())).isEqualTo("ASSIGNED");

		MockHttpServletResponse down = postAs(patient(s.patientId()), s.id(), "join", null);
		assertThat(down.getStatus()).isEqualTo(502);
		assertThat(JSON.readTree(down.getContentAsString()).get("title").asString()).isEqualTo("Video provider error");
		assertThat(jdbc.queryForObject("select joined_at from consultations where id = ?", Instant.class, s.id())).isNull();
		assertThat(jdbc.queryForObject("select session_id from consultations where id = ?", String.class, s.id())).isNull();

		FAILURES.clear(); // provider back
		MockHttpServletResponse ok = postAs(patient(s.patientId()), s.id(), "join", null);
		assertThat(ok.getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(ok.getContentAsString()).get("sessionId").asString()).isEqualTo("s-" + s.id());
	}

	// Review fix: the provider call must not hold the row lock / a DB connection
	@Test
	void slowProviderDoesNotBlockConcurrentCancel() throws Exception {
		Seeded s = seedWaiting();
		assignments.onPatientAssigned(assigned(s, UUID.randomUUID()));
		DELAY_MS = 2500;
		var join = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				return postAs(patient(s.patientId()), s.id(), "join", null).getStatus();
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		});
		Thread.sleep(500); // join is now inside the provider call
		long t0 = System.nanoTime();
		MockHttpServletResponse cancel = mvc.perform(post("/api/v1/consultations/" + s.id() + "/cancel").with(patient(s.patientId())))
			.andReturn().getResponse();
		long ms = (System.nanoTime() - t0) / 1_000_000;
		assertThat(cancel.getStatus()).isEqualTo(200);
		assertThat(ms).isLessThan(1000);
		assertThat(join.get()).isEqualTo(409); // cancelled meanwhile; orphan provider session is accepted
		assertThat(status(s.id())).isEqualTo("CANCELLED");
	}

	@Test
	void startCreatesSessionWhenAbsentAndRedeliveryCreatesNoSecond() throws Exception {
		UUID doctorId = UUID.randomUUID();
		Seeded s = seedWaiting();
		jdbc.update("""
				update consultations set status = 'ASSIGNED', doctor_id = ?, assigned_at = now(), assigned_committed_at = now(),
				joined_at = now() where id = ?""", doctorId, s.id());
		FAILURES.addAll(List.of(500, 500, 500));
		assertThat(postAs(doctor(doctorId), s.id(), "start", null).getStatus()).isEqualTo(502);
		assertThat(status(s.id())).isEqualTo("ASSIGNED");

		FAILURES.clear();
		MockHttpServletResponse r = postAs(doctor(doctorId), s.id(), "start", null);
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(r.getContentAsString()).get("joinUrl").asString()).endsWith("?t=dr");
		assertThat(jdbc.queryForObject("select session_id from consultations where id = ?", String.class, s.id()))
			.isEqualTo("s-" + s.id());

		// session already stored: a second assignment event / join never opens another room
		int calls = CREATE_CALLS.get();
		Seeded j = assignedConsultation(UUID.randomUUID());
		postAs(patient(j.patientId()), j.id(), "join", null);
		int afterFirstJoin = CREATE_CALLS.get();
		postAs(patient(j.patientId()), j.id(), "join", null);
		assignments.onPatientAssigned(assigned(j, UUID.randomUUID()));
		assertThat(CREATE_CALLS.get()).isEqualTo(afterFirstJoin);
		assertThat(afterFirstJoin).isEqualTo(calls + 1);
	}

	@Test
	void assignedIsIgnoredForCancelledConsultation() {
		Seeded s = seedWaiting();
		jdbc.update("update consultations set status = 'CANCELLED' where id = ?", s.id());
		assignments.onPatientAssigned(assigned(s, UUID.randomUUID()));
		assertThat(status(s.id())).isEqualTo("CANCELLED");
		assertThat(CREATE_CALLS.get()).isZero();
	}

	@Test
	void joinReturnsPatientUrl() throws Exception {
		Seeded s = assignedConsultation(UUID.randomUUID());
		assertThat(postAs(patient(UUID.randomUUID()), s.id(), "join", null).getStatus()).isEqualTo(403);

		MockHttpServletResponse r = postAs(patient(s.patientId()), s.id(), "join", null);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode body = JSON.readTree(r.getContentAsString());
		assertThat(body.get("consultationId").asString()).isEqualTo(s.id().toString());
		assertThat(body.get("sessionId").asString()).isEqualTo("s-" + s.id());
		assertThat(body.get("joinUrl").asString()).endsWith("?t=pt");
		assertThat(jdbc.queryForObject("select joined_at from consultations where id = ?", Instant.class, s.id())).isNotNull();

		JsonNode view = JSON.readTree(mvc.perform(get("/api/v1/consultations/" + s.id()).with(patient(s.patientId())))
			.andReturn().getResponse().getContentAsString());
		assertThat(view.get("videoSession").get("sessionId").asString()).isEqualTo("s-" + s.id());

		Seeded waiting = seedWaiting();
		assertThat(postAs(patient(waiting.patientId()), waiting.id(), "join", null).getStatus()).isEqualTo(409);
	}

	@Test
	void startBeforeJoinIs409() throws Exception {
		UUID doctorId = UUID.randomUUID();
		Seeded s = assignedConsultation(doctorId);
		assertThat(postAs(doctor(doctorId), s.id(), "start", null).getStatus()).isEqualTo(409);
		assertThat(status(s.id())).isEqualTo("ASSIGNED");
	}

	@Test
	void startByOtherDoctorIs403() throws Exception {
		UUID doctorId = UUID.randomUUID();
		Seeded s = assignedConsultation(doctorId);
		assertThat(postAs(patient(s.patientId()), s.id(), "join", null).getStatus()).isEqualTo(200);
		assertThat(postAs(doctor(UUID.randomUUID()), s.id(), "start", null).getStatus()).isEqualTo(403);
		assertThat(postAs(patient(s.patientId()), s.id(), "start", null).getStatus()).isEqualTo(403);

		MockHttpServletResponse r = postAs(doctor(doctorId), s.id(), "start", null);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode body = JSON.readTree(r.getContentAsString());
		assertThat(body.get("status").asString()).isEqualTo("IN_PROGRESS");
		assertThat(body.get("joinUrl").asString()).endsWith("?t=dr");
		List<JsonNode> started = outbox("consultation.started", s.id());
		assertThat(started).hasSize(1);
		JsonNode p = started.get(0).get("payload");
		assertThat(started.get(0).get("eventType").asString()).isEqualTo("ConsultationStarted");
		assertThat(p.get("sessionId").asString()).isEqualTo("s-" + s.id());
		assertThat(p.get("doctorId").asString()).isEqualTo(doctorId.toString());
		assertThat(p.get("patientId").asString()).isEqualTo(s.patientId().toString());
		assertThat(p.get("startedAt").asString()).isNotBlank();
		assertThat(postAs(doctor(doctorId), s.id(), "start", null).getStatus()).isEqualTo(409);
	}

	@Test
	void completeEmitsConsultationCompleted() throws Exception {
		UUID doctorId = UUID.randomUUID();
		Seeded s = assignedConsultation(doctorId);
		postAs(patient(s.patientId()), s.id(), "join", null);
		String body = """
				{"clinicalSummary":"Suspected angina. Referred to cardiology.","diagnosisCodes":["I20.9"]}""";
		assertThat(postAs(doctor(doctorId), s.id(), "complete", body).getStatus()).isEqualTo(409); // not IN_PROGRESS
		assertThat(postAs(doctor(doctorId), s.id(), "start", null).getStatus()).isEqualTo(200);

		assertThat(postAs(doctor(doctorId), s.id(), "complete", "{\"clinicalSummary\":\" \",\"diagnosisCodes\":[]}").getStatus())
			.isEqualTo(422);
		assertThat(postAs(doctor(doctorId), s.id(), "complete", "{\"clinicalSummary\":\"x\",\"diagnosisCodes\":[\"\"]}").getStatus())
			.isEqualTo(422);
		assertThat(postAs(doctor(UUID.randomUUID()), s.id(), "complete", body).getStatus()).isEqualTo(403);

		MockHttpServletResponse r = postAs(doctor(doctorId), s.id(), "complete", body);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode res = JSON.readTree(r.getContentAsString());
		assertThat(res.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(res.get("completedAt").asString()).isNotBlank();

		List<JsonNode> completed = outbox("consultation.completed", s.id());
		assertThat(completed).hasSize(1);
		JsonNode p = completed.get(0).get("payload");
		assertThat(completed.get(0).get("eventType").asString()).isEqualTo("ConsultationCompleted");
		assertThat(p.get("sessionId").asString()).isEqualTo("s-" + s.id());
		assertThat(p.get("diagnosisCodes").get(0).asString()).isEqualTo("I20.9");
		assertThat(p.get("clinicalSummary").asString()).startsWith("Suspected angina");
		assertThat(p.get("startedAt").asString()).isNotBlank();
		assertThat(p.get("completedAt").asString()).isNotBlank();
		assertThat(ENDED).contains("s-" + s.id());
		assertThat(status(s.id())).isEqualTo("COMPLETED");
	}

	// S-F4-09, R-F4-05
	@Test
	void noJoinWithinTimeoutBecomesNoShow() throws Exception {
		Seeded absent = assignedConsultation(UUID.randomUUID());
		Seeded present = assignedConsultation(UUID.randomUUID());
		postAs(patient(present.patientId()), present.id(), "join", null);
		Thread.sleep(1200);

		noShowJob.run();

		assertThat(status(absent.id())).isEqualTo("NO_SHOW");
		assertThat(status(present.id())).isEqualTo("ASSIGNED");
		List<JsonNode> noShow = outbox("consultation.no-show", absent.id());
		assertThat(noShow).hasSize(1);
		assertThat(noShow.get(0).get("eventType").asString()).isEqualTo("ConsultationNoShow");
		assertThat(noShow.get(0).get("payload").get("assignedAt").asString()).isNotBlank();
		assertThat(noShow.get(0).get("payload").get("noShowAt").asString()).isNotBlank();
		assertThat(postAs(patient(absent.patientId()), absent.id(), "join", null).getStatus()).isEqualTo(409);
	}

	// Review fix 1: a PatientAssigned delayed by provider retries must not shorten the patient's join window
	@Test
	void noShowTimerStartsWhenAssignedIsCommitted() throws Exception {
		Seeded late = seedWaiting();
		Instant claimedAt = Instant.now().minus(10, java.time.temporal.ChronoUnit.MINUTES).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		assignments.onPatientAssigned(assigned(late, UUID.randomUUID(), claimedAt));

		noShowJob.run();
		assertThat(status(late.id())).isEqualTo("ASSIGNED");
		// assignedAt in the API/events keeps the claim time from the event
		assertThat(jdbc.queryForObject("select assigned_at from consultations where id = ?", Instant.class, late.id()))
			.isEqualTo(claimedAt);

		Thread.sleep(1200);
		noShowJob.run();
		assertThat(status(late.id())).isEqualTo("NO_SHOW");
	}

	@Test
	void repeatedJoinReturnsSameUrl() throws Exception {
		Seeded s = assignedConsultation(UUID.randomUUID());
		JsonNode first = JSON.readTree(postAs(patient(s.patientId()), s.id(), "join", null).getContentAsString());
		Instant joinedAt = jdbc.queryForObject("select joined_at from consultations where id = ?", Instant.class, s.id());
		long version = jdbc.queryForObject("select version from consultations where id = ?", Long.class, s.id());
		MockHttpServletResponse again = postAs(patient(s.patientId()), s.id(), "join", null);
		assertThat(again.getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(again.getContentAsString())).isEqualTo(first);
		assertThat(jdbc.queryForObject("select joined_at from consultations where id = ?", Instant.class, s.id())).isEqualTo(joinedAt);
		assertThat(jdbc.queryForObject("select version from consultations where id = ?", Long.class, s.id())).isEqualTo(version);
	}

	@Test
	void joinWithoutProviderSessionIs502() throws Exception {
		// ASSIGNED without a stored session and the provider down: spec "502 Video provider error"
		Seeded s = seedWaiting();
		jdbc.update("update consultations set status = 'ASSIGNED', doctor_id = ?, assigned_at = now() where id = ?",
				UUID.randomUUID(), s.id());
		FAILURES.addAll(List.of(500, 500, 500));
		MockHttpServletResponse r = postAs(patient(s.patientId()), s.id(), "join", null);
		assertThat(r.getStatus()).isEqualTo(502);
		assertThat(jdbc.queryForObject("select joined_at from consultations where id = ?", Instant.class, s.id())).isNull();
	}

	// spec §4: care-queue rebuilds Redis from this snapshot
	@Test
	void waitingSnapshotRequiresInternalToken() throws Exception {
		Seeded s = seedWaiting();
		assertThat(mvc.perform(get("/internal/v1/consultations/waiting")).andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mvc.perform(get("/internal/v1/consultations/waiting").header("X-Internal-Token", "wrong"))
			.andReturn().getResponse().getStatus()).isEqualTo(401);

		MockHttpServletResponse r = mvc.perform(get("/internal/v1/consultations/waiting").header("X-Internal-Token", "test-internal-token"))
			.andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode row = null;
		for (JsonNode n : JSON.readTree(r.getContentAsString())) {
			if (n.get("consultationId").asString().equals(s.id().toString())) {
				row = n;
			}
		}
		assertThat(row).isNotNull();
		assertThat(row.get("patientId").asString()).isEqualTo(s.patientId().toString());
		assertThat(row.get("priority").asString()).isEqualTo("HIGH");
		assertThat(row.get("triageVersion").asInt()).isEqualTo(1);
		assertThat(row.get("requestedAt").asString()).isNotBlank();
		assertThat(row.get("symptoms").get("mainSymptom").asString()).isEqualTo("CHEST_PAIN");
	}
}
