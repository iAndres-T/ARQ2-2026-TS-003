package com.medconnect.care_queue_service;

import static com.medconnect.care_queue_service.TestContainers.triaged;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.medconnect.care_queue_service.events.TriageEventsListener;
import com.medconnect.care_queue_service.outbox.RedisOutboxRelay;
import com.medconnect.care_queue_service.redis.QueueKeys;
import com.medconnect.common.messaging.MessagingTopology;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = TestContainers.NO_RELAY_POLLING)
@Import(TestContainers.class)
class RedisOutboxRelayIT {

	@Autowired
	RedisOutboxRelay relay;
	@Autowired
	TriageEventsListener triage;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	RabbitTemplate rabbit;
	@Autowired
	RabbitAdmin admin;
	@Autowired
	JsonMapper json;

	Queue probe;

	@BeforeEach
	void setUp() {
		redis.delete(java.util.List.of(QueueKeys.OUTBOX_PENDING, QueueKeys.OUTBOX_INFLIGHT, QueueKeys.RELAY_LOCK));
		probe = new Queue("test.care-queue.probe", true);
		admin.declareQueue(probe);
		admin.purgeQueue(probe.getName(), false);
		admin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange(MessagingTopology.EVENTS_EXCHANGE))
			.with("queue.patient-enqueued"));
	}

	@Test
	void relayPublishesPendingAndClearsInflight() {
		UUID cid = UUID.randomUUID();
		triage.onPatientTriaged(triaged(cid, "HIGH", 1, Instant.now()));
		// leftover of a relay that crashed after moving a record to inflight
		UUID orphan = UUID.randomUUID();
		triage.onPatientTriaged(triaged(orphan, "LOW", 1, Instant.now()));
		redis.opsForList().rightPush(QueueKeys.OUTBOX_INFLIGHT, redis.opsForList().rightPop(QueueKeys.OUTBOX_PENDING));

		relay.publishPending();

		Set<String> received = new HashSet<>();
		for (int i = 0; i < 2; i++) {
			Message message = rabbit.receive(probe.getName(), 5_000);
			assertThat(message).isNotNull();
			assertThat(message.getMessageProperties().getType()).isEqualTo("PatientEnqueued");
			received.add(json.readTree(message.getBody()).get("aggregateId").asString());
		}
		assertThat(received).containsExactlyInAnyOrder(cid.toString(), orphan.toString());
		assertThat(redis.opsForList().size(QueueKeys.OUTBOX_PENDING)).isZero();
		assertThat(redis.opsForList().size(QueueKeys.OUTBOX_INFLIGHT)).isZero();
	}

	@Test
	void inflightLeftoverOfFailedRunIsPublishedWhileLockIsHeld() {
		relay.publishPending(); // acquires the lock; nothing to publish
		UUID cid = UUID.randomUUID();
		triage.onPatientTriaged(triaged(cid, "MEDIUM", 1, Instant.now()));
		// a previous run of this same relay failed after LMOVE, leaving the record in flight
		redis.opsForList().rightPush(QueueKeys.OUTBOX_INFLIGHT, redis.opsForList().rightPop(QueueKeys.OUTBOX_PENDING));

		relay.publishPending();

		Message message = rabbit.receive(probe.getName(), 5_000);
		assertThat(message).isNotNull();
		assertThat(json.readTree(message.getBody()).get("aggregateId").asString()).isEqualTo(cid.toString());
		assertThat(redis.opsForList().size(QueueKeys.OUTBOX_INFLIGHT)).isZero();
	}

	@Test
	void onePublishPendingCallDrainsMoreThanOneBatch() {
		for (int i = 0; i < 250; i++) {
			redis.opsForList().rightPush(QueueKeys.OUTBOX_PENDING, """
					{"exchange":"medconnect.events","routingKey":"queue.patient-enqueued","traceparent":null,
					 "envelope":{"eventId":"%s","eventType":"PatientEnqueued","correlationId":"%s","payload":{}}}"""
				.formatted(UUID.randomUUID(), UUID.randomUUID()));
		}

		relay.publishPending();

		assertThat(redis.opsForList().size(QueueKeys.OUTBOX_PENDING)).isZero();
		assertThat(redis.opsForList().size(QueueKeys.OUTBOX_INFLIGHT)).isZero();
	}

	@Test
	void unroutableStaysPending() {
		String record = """
				{"exchange":"medconnect.events","routingKey":"nobody.listens","traceparent":null,
				 "envelope":{"eventId":"%s","eventType":"Nothing","correlationId":"%s","payload":{}}}"""
			.formatted(UUID.randomUUID(), UUID.randomUUID());
		redis.opsForList().rightPush(QueueKeys.OUTBOX_PENDING, record);

		relay.publishPending();

		assertThat(redis.opsForList().range(QueueKeys.OUTBOX_PENDING, 0, -1)).containsExactly(record);
		assertThat(redis.opsForList().size(QueueKeys.OUTBOX_INFLIGHT)).isZero();
	}
}
