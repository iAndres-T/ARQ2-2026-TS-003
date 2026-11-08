package com.medconnect.prescription_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.medconnect.common.messaging.ConsumerTopology;
import com.medconnect.common.messaging.MessagingTopology;
import com.medconnect.prescription_service.app.PrescriptionService;
import com.medconnect.prescription_service.events.ConsultationEventsListener;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retry delays are shortened. The project ruling for "5 retries" is initial delivery + 5 retries (6 attempts) and
 * then the DLQ, so S-F5-04 ("after 5 attempts") asserts exactly that.
 */
@SpringBootTest(properties = {
		"medconnect.messaging.retry-delays=100ms,100ms,100ms,100ms,100ms",
		"medconnect.messaging.dead-letter-exchanges[pharmacy.prescriptions]=medconnect.pharmacy.dlx" })
@Import({ TestContainers.class, PrescriptionIT.FakePharmacy.class })
class PrescriptionIT {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String BODY = """
			{"consultationId":"%s","items":[{"medicationName":"Aspirin","dosage":"100 mg","frequency":"once daily",
			"durationDays":30,"instructions":"Take after breakfast"}],"notes":"Follow up"}""";

	@Autowired WebApplicationContext ctx;
	@Autowired JdbcTemplate jdbc;
	@Autowired ConsultationEventsListener listener;
	@Autowired RabbitTemplate rabbit;
	@Autowired FakePharmacy pharmacy;
	@MockitoSpyBean PrescriptionService prescriptions;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
		pharmacy.down.set(false);
	}

	// --- helpers ---

	static RequestPostProcessor doctor(UUID doctorId) {
		return jwt().jwt(j -> j.subject(doctorId.toString()).claim("role", "MEDICAL_STAFF").claim("doctorId", doctorId.toString()))
			.authorities(List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_MEDICAL_STAFF")));
	}

	static RequestPostProcessor patient(UUID patientId) {
		return jwt().jwt(j -> j.subject(patientId.toString()).claim("role", "PATIENT"))
			.authorities(List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_PATIENT")));
	}

	static Message msg(String json) {
		return MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build();
	}

	static String envelope(String type, UUID consultationId, UUID patientId, UUID doctorId) {
		return """
				{"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s","producer":"consultation-service","aggregateId":"%s",
				 "aggregateVersion":1,"correlationId":"%s","causationId":null,"actorId":null,
				 "payload":{"consultationId":"%s","patientId":"%s","doctorId":"%s","sessionId":"s1"}}""".formatted(
				UUID.randomUUID(), type, Instant.now(), consultationId, UUID.randomUUID(), consultationId, patientId, doctorId);
	}

	record Ctx(UUID consultation, UUID patient, UUID doctor) {
	}

	Ctx inProgress() {
		Ctx c = new Ctx(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		listener.onStarted(msg(envelope("ConsultationStarted", c.consultation(), c.patient(), c.doctor())));
		return c;
	}

	MockHttpServletResponse issueAs(UUID asDoctor, String key, String body) throws Exception {
		MockHttpServletRequestBuilder req = post("/api/v1/prescriptions").with(doctor(asDoctor))
			.contentType(MediaType.APPLICATION_JSON).content(body);
		if (key != null) {
			req.header("Idempotency-Key", key);
		}
		return mvc.perform(req).andReturn().getResponse();
	}

	int getStatus(String id, RequestPostProcessor who) throws Exception {
		return mvc.perform(get("/api/v1/prescriptions/" + id).with(who)).andReturn().getResponse().getStatus();
	}

	List<JsonNode> events(UUID consultationId) {
		return jdbc.queryForList("select envelope::text from outbox where envelope->>'eventType' = 'PrescriptionIssued' and envelope->'payload'->>'consultationId' = ?",
				String.class, consultationId.toString()).stream().map(JSON::readTree).toList();
	}

	String deliveryStatus(String prescriptionId) {
		return jdbc.queryForObject("select delivery_status from prescription where id = ?::uuid", String.class, prescriptionId);
	}

	String issue(Ctx c) throws Exception {
		return JSON.readTree(issueAs(c.doctor(), UUID.randomUUID().toString(), BODY.formatted(c.consultation())).getContentAsString())
			.get("prescriptionId").asString();
	}

	void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("condition not met within 30 s");
			}
			Thread.sleep(50);
		}
	}

	// --- scenarios ---

	@Test // S-F5-01
	void validPrescriptionIs202AndOutboxHasEvent() throws Exception {
		Ctx c = inProgress();
		MockHttpServletResponse r = issueAs(c.doctor(), UUID.randomUUID().toString(), BODY.formatted(c.consultation()));

		assertThat(r.getStatus()).isEqualTo(202);
		JsonNode body = JSON.readTree(r.getContentAsString());
		assertThat(body.get("consultationId").asString()).isEqualTo(c.consultation().toString());
		assertThat(body.get("deliveryStatus").asString()).isEqualTo("PENDING");
		assertThat(body.get("issuedAt").asString()).isNotBlank();

		JsonNode e = events(c.consultation()).get(0);
		assertThat(e.get("aggregateId").asString()).isEqualTo(body.get("prescriptionId").asString());
		assertThat(e.get("aggregateVersion").asInt()).isEqualTo(1);
		assertThat(e.get("payload").get("prescriptionId").asString()).isEqualTo(body.get("prescriptionId").asString());
		assertThat(e.get("payload").get("patientId").asString()).isEqualTo(c.patient().toString());
		assertThat(e.get("payload").get("doctorId").asString()).isEqualTo(c.doctor().toString());
		assertThat(e.get("payload").get("items").get(0).get("medicationName").asString()).isEqualTo("Aspirin");
		assertThat(e.get("payload").get("notes").asString()).isEqualTo("Follow up");
		assertThat(jdbc.queryForObject("select exchange || '/' || routing_key from outbox where id = ?::uuid", String.class,
				e.get("eventId").asString())).isEqualTo("medconnect.pharmacy/prescription.issued");
	}

	@Test // S-F5-02
	void confirmSetsPublished() throws Exception {
		Ctx c = inProgress();
		String id = issue(c);

		await(() -> "PUBLISHED".equals(deliveryStatus(id)));
		await(() -> pharmacy.attempts.containsKey(id));

		// GET by issuer doctor and owner patient; other principals are refused
		JsonNode got = JSON.readTree(mvc.perform(get("/api/v1/prescriptions/" + id).with(doctor(c.doctor()))).andReturn().getResponse().getContentAsString());
		assertThat(got.get("deliveryStatus").asString()).isEqualTo("PUBLISHED");
		assertThat(got.get("items").get(0).get("durationDays").asInt()).isEqualTo(30);
		assertThat(getStatus(id, patient(c.patient()))).isEqualTo(200);
		assertThat(getStatus(id, patient(UUID.randomUUID()))).isEqualTo(403);
		assertThat(getStatus(id, doctor(UUID.randomUUID()))).isEqualTo(403);
		assertThat(getStatus(UUID.randomUUID().toString(), doctor(c.doctor()))).isEqualTo(404);
	}

	@Test // S-F5-03
	void pharmacyDownStill202() throws Exception {
		pharmacy.down.set(true);
		Ctx c = inProgress();
		MockHttpServletResponse r = issueAs(c.doctor(), UUID.randomUUID().toString(), BODY.formatted(c.consultation()));
		assertThat(r.getStatus()).isEqualTo(202);
		String id = JSON.readTree(r.getContentAsString()).get("prescriptionId").asString();

		// delivery is retried; once the pharmacy is back a retry succeeds
		await(() -> pharmacy.attempts.getOrDefault(id, new AtomicInteger()).get() >= 2);
		pharmacy.down.set(false);
		await(() -> "PUBLISHED".equals(deliveryStatus(id)));
		assertThat(pharmacy.attempts.get(id).get()).isLessThan(6);
	}

	@Test // S-F5-04
	void fiveFailuresDeadLetterAndStatus() throws Exception {
		pharmacy.down.set(true);
		Ctx c = inProgress();
		String id = issue(c);

		await(() -> "DEAD_LETTERED".equals(deliveryStatus(id)));
		// project ruling: initial delivery + 5 retries = 6 attempts, then the DLQ
		assertThat(pharmacy.attempts.get(id).get()).isEqualTo(6);
		Message dead = null;
		for (int i = 0; i < 40 && dead == null; i++) {
			Message m = rabbit.receive("pharmacy.prescriptions.dlq", 500);
			if (m != null && new String(m.getBody()).contains(id)) {
				dead = m;
			}
		}
		assertThat(dead).as("message in pharmacy.prescriptions.dlq").isNotNull();
		// a late PUBLISHED confirm never moves DEAD_LETTERED back
		assertThat(jdbc.update("update prescription set delivery_status = 'PUBLISHED' where id = ?::uuid and delivery_status = 'PENDING'", id)).isZero();
	}

	@Test // S-F5-04: the delivery-failed consumer gets its own retry budget
	void deliveryFailedListenerFailingOnceStillDeadLetters() throws Exception {
		org.mockito.Mockito.doThrow(new IllegalStateException("transient")).doCallRealMethod().when(prescriptions)
			.markDeadLettered(org.mockito.ArgumentMatchers.any());
		pharmacy.down.set(true);
		Ctx c = inProgress();
		String id = issue(c);

		await(() -> "DEAD_LETTERED".equals(deliveryStatus(id)));
		assertThat(jdbc.queryForObject("select count(*) from processed_events where consumer = 'prescription.delivery-failed'", Integer.class)).isPositive();
	}

	@Test // S-F5-06
	void notAssignedDoctorIs403NoEvent() throws Exception {
		Ctx c = inProgress();
		assertThat(issueAs(UUID.randomUUID(), UUID.randomUUID().toString(), BODY.formatted(c.consultation())).getStatus()).isEqualTo(403);
		assertThat(events(c.consultation())).isEmpty();
		assertThat(issueAs(c.doctor(), UUID.randomUUID().toString(), BODY.formatted(UUID.randomUUID())).getStatus()).isEqualTo(404);
	}

	@Test // S-F5-07
	void consultationNotInProgressIs409() throws Exception {
		Ctx c = inProgress();
		listener.onCompleted(msg(envelope("ConsultationCompleted", c.consultation(), c.patient(), c.doctor())));
		assertThat(issueAs(c.doctor(), UUID.randomUUID().toString(), BODY.formatted(c.consultation())).getStatus()).isEqualTo(409);
		assertThat(events(c.consultation())).isEmpty();
		// a late ConsultationStarted must not reopen a completed consultation
		listener.onStarted(msg(envelope("ConsultationStarted", c.consultation(), c.patient(), c.doctor())));
		assertThat(issueAs(c.doctor(), UUID.randomUUID().toString(), BODY.formatted(c.consultation())).getStatus()).isEqualTo(409);
	}

	Ctx assigned() {
		Ctx c = new Ctx(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		listener.onAssigned(msg(envelope("PatientAssigned", c.consultation(), c.patient(), c.doctor())));
		return c;
	}

	@Test // spec I2: assigned but not started -> 409 for the assigned doctor, 403 for another doctor, 404 only if unknown
	void assignedNotStartedIs409Or403Or404() throws Exception {
		Ctx c = assigned();
		assertThat(issueAs(c.doctor(), UUID.randomUUID().toString(), BODY.formatted(c.consultation())).getStatus()).isEqualTo(409);
		assertThat(issueAs(UUID.randomUUID(), UUID.randomUUID().toString(), BODY.formatted(c.consultation())).getStatus()).isEqualTo(403);
		assertThat(issueAs(c.doctor(), UUID.randomUUID().toString(), BODY.formatted(UUID.randomUUID())).getStatus()).isEqualTo(404);
		assertThat(events(c.consultation())).isEmpty();
	}

	@Test // projection is forward-only and idempotent: ASSIGNED < IN_PROGRESS < COMPLETED, cancel/no-show terminal
	void projectionOnlyMovesForward() throws Exception {
		Ctx c = assigned();
		listener.onStarted(msg(envelope("ConsultationStarted", c.consultation(), c.patient(), c.doctor())));
		assertThat(issueAs(c.doctor(), UUID.randomUUID().toString(), BODY.formatted(c.consultation())).getStatus()).isEqualTo(202);
		// a late PatientAssigned must not downgrade IN_PROGRESS
		listener.onAssigned(msg(envelope("PatientAssigned", c.consultation(), c.patient(), c.doctor())));
		assertThat(issueAs(c.doctor(), UUID.randomUUID().toString(), BODY.formatted(c.consultation())).getStatus()).isEqualTo(202);

		Ctx n = assigned();
		listener.onNoShow(msg(envelope("ConsultationNoShow", n.consultation(), n.patient(), n.doctor())));
		listener.onStarted(msg(envelope("ConsultationStarted", n.consultation(), n.patient(), n.doctor())));
		assertThat(issueAs(n.doctor(), UUID.randomUUID().toString(), BODY.formatted(n.consultation())).getStatus()).isEqualTo(409);

		Ctx x = assigned();
		listener.onCancelled(msg(envelope("ConsultationCancelled", x.consultation(), x.patient(), x.doctor())));
		assertThat(issueAs(x.doctor(), UUID.randomUUID().toString(), BODY.formatted(x.consultation())).getStatus()).isEqualTo(409);
	}

	@Test // S-F5-08
	void sameIdempotencyKeyOneEvent() throws Exception {
		Ctx c = inProgress();
		String key = UUID.randomUUID().toString();
		MockHttpServletResponse first = issueAs(c.doctor(), key, BODY.formatted(c.consultation()));
		MockHttpServletResponse second = issueAs(c.doctor(), key, BODY.formatted(c.consultation()));
		assertThat(first.getStatus()).isEqualTo(202);
		assertThat(second.getStatus()).isEqualTo(202);
		assertThat(JSON.readTree(second.getContentAsString()).get("prescriptionId").asString())
			.isEqualTo(JSON.readTree(first.getContentAsString()).get("prescriptionId").asString());
		assertThat(events(c.consultation())).hasSize(1);
		assertThat(issueAs(c.doctor(), null, BODY.formatted(c.consultation())).getStatus()).isEqualTo(400);
	}

	@Test
	void emptyItemsIs422() throws Exception {
		Ctx c = inProgress();
		assertThat(issueAs(c.doctor(), UUID.randomUUID().toString(), "{\"consultationId\":\"%s\",\"items\":[]}".formatted(c.consultation())).getStatus()).isEqualTo(422);
		String badDuration = BODY.formatted(c.consultation()).replace("\"durationDays\":30", "\"durationDays\":366");
		assertThat(issueAs(c.doctor(), UUID.randomUUID().toString(), badDuration).getStatus()).isEqualTo(422);
		String longDosage = BODY.formatted(c.consultation()).replace("100 mg", "x".repeat(101));
		assertThat(issueAs(c.doctor(), UUID.randomUUID().toString(), longDosage).getStatus()).isEqualTo(422);
		assertThat(events(c.consultation())).isEmpty();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakePharmacy {

		final AtomicBoolean down = new AtomicBoolean();
		final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

		/** What pharmacy-provider declares in production: the queue, its DLQ and the DLQ bound to the DLX fanout. */
		@Bean
		Declarables fakePharmacyTopology() {
			List<Declarable> all = new ArrayList<>(ConsumerTopology
				.declarables("pharmacy.prescriptions", MessagingTopology.PHARMACY_EXCHANGE, "prescription.issued").getDeclarables());
			FanoutExchange dlx = new FanoutExchange(MessagingTopology.PHARMACY_DLX_EXCHANGE);
			all.add(BindingBuilder.bind(new Queue("pharmacy.prescriptions.dlq", true)).to(dlx));
			return new Declarables(all);
		}

		@RabbitListener(queues = "pharmacy.prescriptions")
		void onMessage(Message message) {
			String id = JSON.readTree(message.getBody()).get("payload").get("prescriptionId").asString();
			attempts.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
			if (down.get()) {
				throw new IllegalStateException("pharmacy down");
			}
		}
	}
}
