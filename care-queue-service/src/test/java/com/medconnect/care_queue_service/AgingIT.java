package com.medconnect.care_queue_service;

import static com.medconnect.care_queue_service.TestContainers.triaged;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.medconnect.care_queue_service.events.TriageEventsListener;
import com.medconnect.care_queue_service.jobs.AgingJob;
import com.medconnect.care_queue_service.redis.QueueKeys;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = TestContainers.NO_RELAY_POLLING)
@Import(TestContainers.class)
class AgingIT {

	@Autowired
	TriageEventsListener triage;
	@Autowired
	AgingJob aging;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	JsonMapper json;

	List<JsonNode> enqueued(UUID cid) {
		return redis.opsForList().range(QueueKeys.OUTBOX_PENDING, 0, -1).stream().map(json::readTree)
			.map(r -> r.get("envelope"))
			.filter(e -> e.get("eventType").asString().equals("PatientEnqueued"))
			.filter(e -> e.get("aggregateId").asString().equals(cid.toString()))
			.toList();
	}

	// S-F4-08, R-F4-04
	@Test
	void agingPromotesLowToMediumKeepingScore() {
		Instant old = Instant.now().minus(91, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
		Instant fresh = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
		Instant ancient = Instant.now().minus(300, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
		UUID agedLow = UUID.randomUUID();
		UUID freshLow = UUID.randomUUID();
		UUID agedMedium = UUID.randomUUID();
		triage.onPatientTriaged(triaged(agedLow, "LOW", 3, old));
		triage.onPatientTriaged(triaged(freshLow, "LOW", 1, fresh));
		triage.onPatientTriaged(triaged(agedMedium, "MEDIUM", 1, ancient));

		aging.promote();
		aging.promote(); // a second run is a no-op

		assertThat(redis.opsForZSet().score("queue:LOW", agedLow.toString())).isNull();
		assertThat(redis.opsForZSet().score("queue:MEDIUM", agedLow.toString())).isEqualTo((double) old.toEpochMilli());
		assertThat(redis.opsForHash().get("entry:" + agedLow, "triageVersion")).isEqualTo("3");
		List<JsonNode> events = enqueued(agedLow);
		assertThat(events).hasSize(2);
		JsonNode payload = events.get(1).get("payload");
		assertThat(payload.get("reason").asString()).isEqualTo("AGING");
		assertThat(payload.get("previousQueue").asString()).isEqualTo("LOW");
		assertThat(payload.get("queue").asString()).isEqualTo("MEDIUM");
		assertThat(Instant.parse(payload.get("requestedAt").asString())).isEqualTo(old);

		assertThat(redis.opsForZSet().score("queue:LOW", freshLow.toString())).isEqualTo((double) fresh.toEpochMilli());
		// aging never promotes to HIGH
		assertThat(redis.opsForZSet().score("queue:MEDIUM", agedMedium.toString())).isEqualTo((double) ancient.toEpochMilli());
		assertThat(redis.opsForZSet().score("queue:HIGH", agedMedium.toString())).isNull();
		assertThat(enqueued(agedMedium)).hasSize(1);
	}
}
