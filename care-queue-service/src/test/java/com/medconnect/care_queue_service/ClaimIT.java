package com.medconnect.care_queue_service;

import static com.medconnect.care_queue_service.TestContainers.cancelled;
import static com.medconnect.care_queue_service.TestContainers.envelope;
import static com.medconnect.care_queue_service.TestContainers.triaged;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.medconnect.care_queue_service.events.ConsultationEventsListener;
import com.medconnect.care_queue_service.events.DoctorEventsListener;
import com.medconnect.care_queue_service.events.TriageEventsListener;
import com.medconnect.care_queue_service.jobs.LeaseRecoveryJob;
import com.medconnect.care_queue_service.redis.QueueKeys;
import com.medconnect.care_queue_service.redis.RedisIdempotencyStore;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = TestContainers.NO_RELAY_POLLING)
@Import(TestContainers.class)
class ClaimIT {

	@Autowired
	WebApplicationContext ctx;
	@Autowired
	TriageEventsListener triage;
	@Autowired
	ConsultationEventsListener consultations;
	@Autowired
	DoctorEventsListener doctors;
	@Autowired
	LeaseRecoveryJob leaseRecovery;
	@Autowired
	RedisScript<String> claimScript;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	JsonMapper json;
	@Autowired
	MeterRegistry meters;
	@MockitoSpyBean
	RedisIdempotencyStore idempotencyStore;

	MockMvc mvc;
	final Instant base = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

	@BeforeEach
	void setUp() {
		redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
			c.serverCommands().flushAll();
			return null;
		});
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	UUID patient(String priority, Instant requestedAt) {
		UUID cid = UUID.randomUUID();
		triage.onPatientTriaged(triaged(cid, priority, 1, requestedAt));
		return cid;
	}

	UUID doctor(String status) {
		UUID did = UUID.randomUUID();
		if (status.equals("FREE")) {
			doctors.onDoctorBecameAvailable(envelope("DoctorBecameAvailable", did, 1,
					"{\"doctorId\":\"%s\",\"shiftId\":\"%s\",\"availableSince\":\"%s\"}".formatted(did, UUID.randomUUID(), Instant.now())));
		}
		else {
			doctors.onDoctorBecameUnavailable(envelope("DoctorBecameUnavailable", did, 1,
					"{\"doctorId\":\"%s\",\"status\":\"%s\",\"since\":\"%s\"}".formatted(did, status, Instant.now())));
		}
		return did;
	}

	MockHttpServletResponse claim(UUID did, String key) throws Exception {
		return mvc.perform(post("/api/v1/queue/claims").header("Idempotency-Key", key)
			.with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "MEDICAL_STAFF").claim("doctorId", did.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_MEDICAL_STAFF"))))
			.andReturn().getResponse();
	}

	MockHttpServletResponse claim(UUID did) throws Exception {
		return claim(did, UUID.randomUUID().toString());
	}

	String claimedCid(MockHttpServletResponse r) throws Exception {
		assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
		return json.readTree(r.getContentAsString()).get("consultationId").asString();
	}

	List<JsonNode> outbox(String eventType) {
		return redis.opsForList().range(QueueKeys.OUTBOX_PENDING, 0, -1).stream().map(json::readTree)
			.map(r -> r.get("envelope")).filter(e -> e.get("eventType").asString().equals(eventType)).toList();
	}

	long waiting() {
		return QueueKeys.PRIORITIES.stream().mapToLong(p -> redis.opsForZSet().zCard(QueueKeys.queue(p))).sum();
	}

	// S-F4-01
	@Test
	void highBeforeTwoHundredLow() throws Exception {
		for (int i = 0; i < 200; i++) {
			patient("LOW", base.plusMillis(i));
		}
		UUID high = patient("HIGH", Instant.now());
		UUID did = doctor("FREE");

		MockHttpServletResponse r = claim(did);

		assertThat(claimedCid(r)).isEqualTo(high.toString());
		JsonNode body = json.readTree(r.getContentAsString());
		assertThat(body.get("priority").asString()).isEqualTo("HIGH");
		assertThat(body.get("doctorId").asString()).isEqualTo(did.toString());
		assertThat(body.get("patientId").isNull()).isFalse();
		assertThat(body.get("symptoms").get("mainSymptom").asString()).isEqualTo("CHEST_PAIN");
		assertThat(body.get("assignedAt").isNull()).isFalse();
		assertThat(redis.opsForZSet().zCard("queue:LOW")).isEqualTo(200);

		List<JsonNode> assigned = outbox("PatientAssigned");
		assertThat(assigned).hasSize(1);
		JsonNode payload = assigned.get(0).get("payload");
		assertThat(payload.get("consultationId").asString()).isEqualTo(high.toString());
		assertThat(payload.get("doctorId").asString()).isEqualTo(did.toString());
		assertThat(payload.get("priority").asString()).isEqualTo("HIGH");
		assertThat(payload.get("patientId").asString()).isEqualTo(body.get("patientId").asString());
		assertThat(redis.opsForHash().get("doctor:" + did, "status")).isEqualTo("BUSY");
		assertThat(redis.opsForHash().get("doctor:" + did, "version")).isEqualTo("1"); // local mark, no version bump
		assertThat(redis.opsForSet().isMember(QueueKeys.DOCTORS_FREE, did.toString())).isFalse();
		assertThat(redis.hasKey("tombstone:" + high)).isTrue();
	}

	// S-F4-02
	@Test
	void oldestMediumWhenHighEmpty() throws Exception {
		patient("LOW", base.minusSeconds(60));
		patient("MEDIUM", base.plusSeconds(5));
		UUID oldest = patient("MEDIUM", base);
		assertThat(claimedCid(claim(doctor("FREE")))).isEqualTo(oldest.toString());
	}

	// S-F4-03
	@Test
	void fifoWithinLevel() throws Exception {
		UUID second = patient("LOW", base.plusSeconds(1));
		UUID first = patient("LOW", base);
		assertThat(claimedCid(claim(doctor("FREE")))).isEqualTo(first.toString());
		assertThat(claimedCid(claim(doctor("FREE")))).isEqualTo(second.toString());
	}

	/** Runs the tasks at the same instant on their own threads. */
	List<MockHttpServletResponse> concurrently(List<Callable<MockHttpServletResponse>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (Callable<MockHttpServletResponse> t : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return t.call();
				}));
			}
			start.countDown();
			List<MockHttpServletResponse> out = new ArrayList<>();
			for (Future<MockHttpServletResponse> f : futures) {
				out.add(f.get());
			}
			return out;
		}
		finally {
			pool.shutdown();
		}
	}

	// S-F4-04
	@Test
	void twoDoctorsOnePatient() throws Exception {
		UUID cid = patient("HIGH", base);
		UUID a = doctor("FREE");
		UUID b = doctor("FREE");

		List<MockHttpServletResponse> rs = concurrently(List.of(() -> claim(a), () -> claim(b)));

		assertThat(rs).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 204);
		assertThat(outbox("PatientAssigned")).hasSize(1)
			.allSatisfy(e -> assertThat(e.get("payload").get("consultationId").asString()).isEqualTo(cid.toString()));
		assertThat(waiting()).isZero();
	}

	// S-F4-05
	@Test
	void emptyQueuesIs204() throws Exception {
		UUID did = doctor("FREE");
		assertThat(claim(did).getStatus()).isEqualTo(204);
		assertThat(redis.opsForHash().get("doctor:" + did, "status")).isEqualTo("FREE");
		assertThat(redis.opsForSet().isMember(QueueKeys.DOCTORS_FREE, did.toString())).isTrue();
	}

	// S-F4-06
	@Test
	void busyDoctorIs409AndQueueUntouched() throws Exception {
		patient("HIGH", base);
		patient("LOW", base);
		for (String status : List.of("BUSY", "OFFLINE")) {
			MockHttpServletResponse r = claim(doctor(status));
			assertThat(r.getStatus()).isEqualTo(409);
			assertThat(r.getContentType()).contains("problem+json");
		}
		assertThat(claim(UUID.randomUUID()).getStatus()).isEqualTo(409); // unknown doctor (no active shift)
		assertThat(waiting()).isEqualTo(2);
		assertThat(redis.opsForZSet().zCard(QueueKeys.CLAIMED)).isZero();
		assertThat(outbox("PatientAssigned")).isEmpty();
	}

	// Review Focus 2: one doctor firing parallel claims gets one patient
	@Test
	void sameDoctorConcurrentClaims() throws Exception {
		for (int i = 0; i < 5; i++) {
			patient("MEDIUM", base.plusMillis(i));
		}
		UUID did = doctor("FREE");
		List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(() -> claim(did));
		}

		List<MockHttpServletResponse> rs = concurrently(tasks);

		assertThat(rs).extracting(MockHttpServletResponse::getStatus).filteredOn(s -> s == 201).hasSize(1);
		assertThat(rs).extracting(MockHttpServletResponse::getStatus).filteredOn(s -> s != 201).containsOnly(409);
		assertThat(waiting()).isEqualTo(4);
		assertThat(outbox("PatientAssigned")).hasSize(1);
	}

	@Test
	void sameIdempotencyKeyReturnsSameAssignment() throws Exception {
		patient("HIGH", base);
		patient("HIGH", base.plusSeconds(1));
		UUID did = doctor("FREE");

		MockHttpServletResponse first = claim(did, "key-1");
		MockHttpServletResponse replay = claim(did, "key-1");

		assertThat(first.getStatus()).isEqualTo(201);
		assertThat(replay.getStatus()).isEqualTo(201);
		assertThat(json.readTree(replay.getContentAsString())).isEqualTo(json.readTree(first.getContentAsString()));
		assertThat(waiting()).isEqualTo(1);
		assertThat(outbox("PatientAssigned")).hasSize(1);
		assertThat(redis.getExpire("idem:" + did + ":key-1")).isGreaterThan(23 * 3600);
	}

	@Test
	void cancelledDuringLeaseIsNotAssigned() throws Exception {
		UUID cancelledCid = patient("HIGH", base);
		UUID did = doctor("FREE");
		// a claim whose confirm has not run yet, then the patient cancels
		assertThat(redis.execute(claimScript, List.of("doctor:" + did, QueueKeys.DOCTORS_FREE, "queue:HIGH", "queue:MEDIUM",
				"queue:LOW", QueueKeys.CLAIMED), did.toString(), String.valueOf(System.currentTimeMillis()), "30000"))
			.isEqualTo(cancelledCid.toString());
		consultations.onConsultationCancelled(cancelled(cancelledCid));
		UUID next = patient("LOW", base);
		UUID other = doctor("FREE");

		// the claiming request died before confirm: the cancel itself frees the first doctor
		assertThat(redis.opsForHash().get("doctor:" + did, "status")).isEqualTo("FREE");
		assertThat(redis.opsForSet().isMember(QueueKeys.DOCTORS_FREE, did.toString())).isTrue();
		assertThat(redis.opsForHash().get("doctor:" + did, "version")).isEqualTo("1");

		assertThat(claimedCid(claim(other))).isEqualTo(next.toString());
		UUID later = patient("MEDIUM", base);
		assertThat(claimedCid(claim(did))).isEqualTo(later.toString());
		assertThat(outbox("PatientAssigned")).hasSize(2);
	}

	// spec §2: the assignment is committed but the stored response is lost (complete fails / reply times out)
	@Test
	void retryAfterLostResponseReturnsSameAssignment() throws Exception {
		UUID cid = patient("HIGH", base);
		patient("HIGH", base.plusSeconds(1));
		UUID did = doctor("FREE");
		doThrow(new QueryTimeoutException("reply lost")).doCallRealMethod()
			.when(idempotencyStore).complete(eq("key-lost"), eq(did), anyInt(), any());

		assertThat(claim(did, "key-lost").getStatus()).isEqualTo(503);
		MockHttpServletResponse retry = claim(did, "key-lost");

		assertThat(claimedCid(retry)).isEqualTo(cid.toString());
		JsonNode body = json.readTree(retry.getContentAsString());
		assertThat(body.get("doctorId").asString()).isEqualTo(did.toString());
		assertThat(body.get("symptoms").get("mainSymptom").asString()).isEqualTo("CHEST_PAIN");
		assertThat(outbox("PatientAssigned")).hasSize(1);
		assertThat(waiting()).isEqualTo(1);
	}

	// S-F4-12 (lease 200 ms): the service died after claim.lua and before confirm.lua
	@Test
	void expiredLeaseRequeuesWithOriginalScore() throws Exception {
		UUID cid = patient("MEDIUM", base);
		UUID did = doctor("FREE");
		redis.delete(QueueKeys.OUTBOX_PENDING);
		assertThat(redis.execute(claimScript, List.of("doctor:" + did, QueueKeys.DOCTORS_FREE, "queue:HIGH", "queue:MEDIUM",
				"queue:LOW", QueueKeys.CLAIMED), did.toString(), String.valueOf(System.currentTimeMillis()), "200"))
			.isEqualTo(cid.toString());
		assertThat(redis.opsForHash().get("doctor:" + did, "status")).isEqualTo("CLAIMING");
		assertThat(redis.opsForSet().isMember(QueueKeys.DOCTORS_FREE, did.toString())).isFalse();
		assertThat(waiting()).isZero();

		Thread.sleep(300);
		leaseRecovery.recover();

		assertThat(redis.opsForZSet().score("queue:MEDIUM", cid.toString())).isEqualTo((double) base.toEpochMilli());
		assertThat(redis.opsForZSet().zCard(QueueKeys.CLAIMED)).isZero();
		assertThat(redis.opsForHash().get("doctor:" + did, "status")).isEqualTo("FREE");
		assertThat(redis.opsForSet().isMember(QueueKeys.DOCTORS_FREE, did.toString())).isTrue();
		List<JsonNode> enqueued = outbox("PatientEnqueued");
		assertThat(enqueued).hasSize(1);
		JsonNode payload = enqueued.get(0).get("payload");
		assertThat(payload.get("queue").asString()).isEqualTo("MEDIUM");
		assertThat(payload.get("previousQueue").isNull()).isTrue();
		assertThat(payload.get("reason").asString()).isEqualTo("INITIAL");
		assertThat(Instant.parse(payload.get("requestedAt").asString())).isEqualTo(base);
		assertThat(outbox("PatientAssigned")).isEmpty();
		// the patient is claimable again
		assertThat(claimedCid(claim(did))).isEqualTo(cid.toString());
	}

	@Test
	void statsReportsDepthOldestAndFreeDoctors() throws Exception {
		patient("HIGH", Instant.now().minusSeconds(95));
		patient("LOW", Instant.now().minusSeconds(600));
		patient("LOW", Instant.now().minusSeconds(30));
		doctor("FREE");
		doctor("FREE");
		doctor("BUSY");

		MockHttpServletResponse r = mvc.perform(get("/api/v1/queue/stats")
			.with(jwt().jwt(j -> j.claim("role", "MEDICAL_STAFF")).authorities(new SimpleGrantedAuthority("ROLE_MEDICAL_STAFF"))))
			.andReturn().getResponse();

		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode body = json.readTree(r.getContentAsString());
		assertThat(body.get("freeDoctors").asInt()).isEqualTo(2);
		assertThat(body.get("generatedAt").isNull()).isFalse();
		JsonNode q = body.get("queues");
		assertThat(q.get("HIGH").get("waiting").asInt()).isEqualTo(1);
		assertThat(q.get("HIGH").get("oldestWaitSeconds").asLong()).isBetween(94L, 100L);
		assertThat(q.get("MEDIUM").get("waiting").asInt()).isZero();
		assertThat(q.get("MEDIUM").get("oldestWaitSeconds").asLong()).isZero();
		assertThat(q.get("LOW").get("waiting").asInt()).isEqualTo(2);
		assertThat(q.get("LOW").get("oldestWaitSeconds").asLong()).isBetween(599L, 605L);

		assertThat(meters.get("medconnect.queue.depth").tag("level", "LOW").gauge().value()).isEqualTo(2);
		assertThat(meters.get("medconnect.queue.oldest.wait.seconds").tag("level", "HIGH").gauge().value()).isBetween(94.0, 100.0);

		MockHttpServletResponse patient = mvc.perform(get("/api/v1/queue/stats")
			.with(jwt().jwt(j -> j.claim("role", "PATIENT")).authorities(new SimpleGrantedAuthority("ROLE_PATIENT"))))
			.andReturn().getResponse();
		assertThat(patient.getStatus()).isEqualTo(403);
	}
}
