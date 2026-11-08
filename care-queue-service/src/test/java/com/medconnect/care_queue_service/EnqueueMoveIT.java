package com.medconnect.care_queue_service;

import static com.medconnect.care_queue_service.TestContainers.cancelled;
import static com.medconnect.care_queue_service.TestContainers.envelope;
import static com.medconnect.care_queue_service.TestContainers.overridden;
import static com.medconnect.care_queue_service.TestContainers.triaged;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.medconnect.care_queue_service.events.ConsultationEventsListener;
import com.medconnect.care_queue_service.events.DoctorEventsListener;
import com.medconnect.care_queue_service.events.TriageEventsListener;
import com.medconnect.care_queue_service.redis.QueueKeys;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = TestContainers.NO_RELAY_POLLING)
@Import(TestContainers.class)
class EnqueueMoveIT {

	static final List<String> PRIORITIES = List.of("HIGH", "MEDIUM", "LOW");

	@Autowired
	TriageEventsListener triage;
	@Autowired
	ConsultationEventsListener consultations;
	@Autowired
	DoctorEventsListener doctors;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	JsonMapper json;

	final Instant requestedAt = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

	Double score(String priority, UUID cid) {
		return redis.opsForZSet().score("queue:" + priority, cid.toString());
	}

	/** Sum of ZSCORE hits across the three zsets, read atomically so a concurrent move cannot skew it. */
	long atomicHits(UUID cid) {
		return redis.execute(RedisScript.of("""
				local n = 0
				for i = 1, 3 do if redis.call('ZSCORE', KEYS[i], ARGV[1]) then n = n + 1 end end
				return n""", Long.class), List.of("queue:HIGH", "queue:MEDIUM", "queue:LOW"), cid.toString());
	}

	List<String> queuesHolding(UUID cid) {
		return PRIORITIES.stream().filter(p -> score(p, cid) != null).toList();
	}

	/** PatientEnqueued envelopes for this consultation still waiting in the Redis outbox. */
	List<JsonNode> enqueuedEvents(UUID cid) {
		List<String> pending = redis.opsForList().range(QueueKeys.OUTBOX_PENDING, 0, -1);
		return pending.stream()
			.map(json::readTree)
			.map(record -> record.get("envelope"))
			.filter(e -> e.get("eventType").asString().equals("PatientEnqueued"))
			.filter(e -> e.get("aggregateId").asString().equals(cid.toString()))
			.toList();
	}

	@Test
	void triagedHighGoesToHighWithRequestedAtScore() {
		UUID cid = UUID.randomUUID();
		triage.onPatientTriaged(triaged(cid, "HIGH", 1, requestedAt));

		assertThat(score("HIGH", cid)).isEqualTo((double) requestedAt.toEpochMilli());
		assertThat(queuesHolding(cid)).containsExactly("HIGH");
		assertThat(redis.opsForHash().entries("entry:" + cid)).containsKeys("patientId", "symptoms", "requestedAt")
			.containsEntry("queue", "HIGH")
			.containsEntry("triageVersion", "1");
		List<JsonNode> events = enqueuedEvents(cid);
		assertThat(events).hasSize(1);
		JsonNode payload = events.get(0).get("payload");
		assertThat(payload.get("reason").asString()).isEqualTo("INITIAL");
		assertThat(payload.get("previousQueue").isNull()).isTrue();
		assertThat(payload.get("queue").asString()).isEqualTo("HIGH");
		assertThat(Instant.parse(payload.get("requestedAt").asString())).isEqualTo(requestedAt);
		assertThat(payload.get("triageVersion").asInt()).isEqualTo(1);
		assertThat(events.get(0).get("producer").asString()).isEqualTo("care-queue-service");
	}

	@Test
	void duplicateEventIdIsSkipped() {
		UUID cid = UUID.randomUUID();
		Message message = triaged(cid, "LOW", 1, requestedAt);
		triage.onPatientTriaged(message);
		triage.onPatientTriaged(message);
		assertThat(enqueuedEvents(cid)).hasSize(1);
	}

	// S-F3-07
	@Test
	void overrideMovesAtomicallyKeepingScore() {
		UUID cid = UUID.randomUUID();
		triage.onPatientTriaged(triaged(cid, "MEDIUM", 1, requestedAt));
		triage.onPriorityOverridden(overridden(cid, "MEDIUM", "HIGH", 2, requestedAt));

		assertThat(queuesHolding(cid)).containsExactly("HIGH");
		assertThat(score("HIGH", cid)).isEqualTo((double) requestedAt.toEpochMilli());
		List<JsonNode> events = enqueuedEvents(cid);
		assertThat(events).hasSize(2);
		JsonNode move = events.get(1);
		assertThat(move.get("payload").get("reason").asString()).isEqualTo("OVERRIDE");
		assertThat(move.get("payload").get("previousQueue").asString()).isEqualTo("MEDIUM");
		assertThat(move.get("payload").get("queue").asString()).isEqualTo("HIGH");
		assertThat(move.get("aggregateVersion").asLong()).isEqualTo(2);
		assertThat(move.get("actorId").isNull()).isFalse();
	}

	// S-F3-08
	@Test
	void reTriageToOtherPriorityMovesWithReasonReclassified() {
		UUID cid = UUID.randomUUID();
		triage.onPatientTriaged(triaged(cid, "LOW", 1, requestedAt));
		triage.onPatientTriaged(triaged(cid, "MEDIUM", 2, requestedAt));
		triage.onPatientTriaged(triaged(cid, "MEDIUM", 3, requestedAt));

		assertThat(queuesHolding(cid)).containsExactly("MEDIUM");
		assertThat(redis.opsForHash().get("entry:" + cid, "triageVersion")).isEqualTo("3");
		List<JsonNode> events = enqueuedEvents(cid);
		assertThat(events).hasSize(2); // same-queue v3 is UNCHANGED, no event
		assertThat(events.get(1).get("payload").get("reason").asString()).isEqualTo("RECLASSIFIED");
	}

	// S-F3-10
	@Test
	void staleTriageVersionIgnored() {
		UUID cid = UUID.randomUUID();
		triage.onPriorityOverridden(overridden(cid, "MEDIUM", "HIGH", 3, requestedAt));
		triage.onPatientTriaged(triaged(cid, "LOW", 2, requestedAt));

		assertThat(queuesHolding(cid)).containsExactly("HIGH");
		assertThat(redis.opsForHash().get("entry:" + cid, "triageVersion")).isEqualTo("3");
		assertThat(enqueuedEvents(cid)).hasSize(1);
	}

	// R-F3-03: concurrent moves never leave the patient in two queues
	@Test
	void neverInTwoQueues() throws Exception {
		UUID cid = UUID.randomUUID();
		triage.onPatientTriaged(triaged(cid, "LOW", 1, requestedAt));
		List<Integer> versions = new ArrayList<>(IntStream.rangeClosed(2, 101).boxed().toList());
		Collections.shuffle(versions);
		String[] priorityOf = new String[102];
		versions.forEach(v -> priorityOf[v] = PRIORITIES.get((int) (Math.random() * 3)));

		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<?>> futures = versions.stream()
				.map(v -> pool.submit(() -> {
					if (v % 2 == 0) {
						triage.onPatientTriaged(triaged(cid, priorityOf[v], v, requestedAt));
					}
					else {
						triage.onPriorityOverridden(overridden(cid, "LOW", priorityOf[v], v, requestedAt));
					}
					assertThat(atomicHits(cid)).isEqualTo(1);
				}))
				.<Future<?>>map(f -> f)
				.toList();
			for (Future<?> f : futures) {
				f.get();
			}
		}
		finally {
			pool.shutdown();
		}

		assertThat(queuesHolding(cid)).containsExactly(priorityOf[101]);
		assertThat(score(priorityOf[101], cid)).isEqualTo((double) requestedAt.toEpochMilli());
		assertThat(redis.opsForHash().get("entry:" + cid, "triageVersion")).isEqualTo("101");
		assertThat(redis.opsForHash().get("entry:" + cid, "queue")).isEqualTo(priorityOf[101]);
	}

	// S-F4-10
	@Test
	void cancelRemovesFromQueue() {
		UUID cid = UUID.randomUUID();
		triage.onPatientTriaged(triaged(cid, "MEDIUM", 1, requestedAt));
		consultations.onConsultationCancelled(cancelled(cid));

		assertThat(queuesHolding(cid)).isEmpty();
		assertThat(redis.hasKey("entry:" + cid)).isFalse();
		assertThat(redis.getExpire("tombstone:" + cid)).isPositive();
		// removing an absent entry is a no-op
		consultations.onConsultationCancelled(cancelled(cid));
	}

	// Review Focus 3: cancellation racing a late PatientTriaged
	@Test
	void cancelledTombstoneBlocksReEnqueue() {
		UUID cid = UUID.randomUUID();
		consultations.onConsultationCancelled(cancelled(cid));
		triage.onPatientTriaged(triaged(cid, "HIGH", 1, requestedAt));
		triage.onPriorityOverridden(overridden(cid, "HIGH", "LOW", 2, requestedAt));

		assertThat(queuesHolding(cid)).isEmpty();
		assertThat(redis.hasKey("entry:" + cid)).isFalse();
		assertThat(enqueuedEvents(cid)).isEmpty();
	}

	@Test
	void doctorAvailableAddsToFreeSet() {
		UUID did = UUID.randomUUID();
		doctors.onDoctorBecameAvailable(envelope("DoctorBecameAvailable", did, 1,
				"{\"doctorId\":\"%s\",\"shiftId\":\"%s\",\"availableSince\":\"%s\"}".formatted(did, UUID.randomUUID(), Instant.now())));
		assertThat(redis.opsForSet().isMember(QueueKeys.DOCTORS_FREE, did.toString())).isTrue();
		assertThat(redis.opsForHash().get("doctor:" + did, "status")).isEqualTo("FREE");

		doctors.onDoctorBecameUnavailable(envelope("DoctorBecameUnavailable", did, 3,
				"{\"doctorId\":\"%s\",\"status\":\"BUSY\",\"since\":\"%s\"}".formatted(did, Instant.now())));
		// stale availability (version 2 < 3) is ignored
		doctors.onDoctorBecameAvailable(envelope("DoctorBecameAvailable", did, 2,
				"{\"doctorId\":\"%s\",\"shiftId\":\"%s\",\"availableSince\":\"%s\"}".formatted(did, UUID.randomUUID(), Instant.now())));

		assertThat(redis.opsForSet().isMember(QueueKeys.DOCTORS_FREE, did.toString())).isFalse();
		assertThat(redis.opsForHash().get("doctor:" + did, "status")).isEqualTo("BUSY");
	}
}
