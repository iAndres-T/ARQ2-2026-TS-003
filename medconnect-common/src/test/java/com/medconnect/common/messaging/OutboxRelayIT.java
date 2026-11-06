package com.medconnect.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.medconnect.common.Await;
import com.medconnect.common.TestContainersConfig;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "medconnect.messaging.outbox-poll-interval=1h")
@Import(TestContainersConfig.class)
class OutboxRelayIT {

	private static final String QUEUE = "test.outbox";

	@Autowired OutboxWriter writer;
	@Autowired OutboxRelay relay;
	@Autowired OutboxPurgeJob purgeJob;
	@Autowired TransactionTemplate tx;
	@Autowired JdbcTemplate jdbc;
	@Autowired AmqpAdmin admin;
	@Autowired RabbitTemplate rabbit;
	@Autowired JsonMapper json;

	@BeforeEach
	void setUp() {
		admin.declareQueue(new Queue(QUEUE));
		admin.declareBinding(BindingBuilder.bind(new Queue(QUEUE)).to(new TopicExchange("medconnect.events")).with("test.#"));
		admin.purgeQueue(QUEUE, false);
		jdbc.update("delete from outbox");
	}

	@Test
	void relayPublishesCommittedRowsOnlyOnce() throws Exception {
		tx.executeWithoutResult(s -> {
			for (int i = 0; i < 50; i++) {
				writer.append("medconnect.events", "test.created", "TestCreated", UUID.randomUUID(), 1, null, Map.of("i", i));
			}
		});

		CompletableFuture<Void> a = CompletableFuture.runAsync(relay::publishPending);
		CompletableFuture<Void> b = CompletableFuture.runAsync(relay::publishPending);
		CompletableFuture.allOf(a, b).join();
		Await.until(Duration.ofSeconds(10), () -> unpublished() == 0);

		List<String> ids = drain();
		assertThat(ids).hasSize(50);
		assertThat(new HashSet<>(ids)).hasSize(50);
	}

	@Test
	void onePublishPendingCallDrainsMoreThanOneBatch() throws Exception {
		tx.executeWithoutResult(s -> {
			for (int i = 0; i < 250; i++) {
				writer.append("medconnect.events", "test.created", "TestCreated", UUID.randomUUID(), 1, null, Map.of("i", i));
			}
		});

		relay.publishPending();

		assertThat(unpublished()).isZero();
		assertThat(drain()).hasSize(250);
	}

	@Test
	void rolledBackTransactionPublishesNothing() throws Exception {
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			writer.append("medconnect.events", "test.created", "TestCreated", UUID.randomUUID(), 1, null, Map.of());
			throw new IllegalStateException("boom");
		})).hasMessage("boom");

		relay.publishPending();

		assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isZero();
		assertThat(drain()).isEmpty();
	}

	@Test
	void unroutableEventStaysUnpublishedWithoutBlockingOthers() throws Exception {
		tx.executeWithoutResult(s -> {
			writer.append("medconnect.events", "nobody.listens", "Orphan", UUID.randomUUID(), 1, null, Map.of());
			writer.append("medconnect.events", "test.created", "TestCreated", UUID.randomUUID(), 1, null, Map.of());
		});

		relay.publishPending();

		assertThat(jdbc.queryForList("select routing_key from outbox where published_at is null", String.class))
			.containsExactly("nobody.listens");
		assertThat(jdbc.queryForObject("select attempts from outbox where routing_key = 'nobody.listens'", Integer.class))
			.isEqualTo(1);
		assertThat(drain()).hasSize(1);
	}

	@Test
	void appendOutsideTransactionIsRejected() {
		assertThatThrownBy(() -> writer.append("medconnect.events", "test.created", "TestCreated", UUID.randomUUID(), 1, null, Map.of()))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void envelopeCarriesSpecFields() throws Exception {
		UUID correlationId = UUID.randomUUID();
		UUID aggregateId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		MDC.put("correlationId", correlationId.toString());
		UUID eventId;
		try {
			eventId = tx.execute(s -> writer.append("medconnect.events", "test.created", "TestCreated", aggregateId, 7, actorId, Map.of("k", "v")));
		}
		finally {
			MDC.remove("correlationId");
		}

		relay.publishPending();
		Message message = rabbit.receive(QUEUE, 5000);
		EventEnvelope envelope = json.readValue(message.getBody(), EventEnvelope.class);

		assertThat(envelope.eventId()).isEqualTo(eventId);
		assertThat(message.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
		assertThat(envelope.eventType()).isEqualTo("TestCreated");
		assertThat(envelope.eventVersion()).isEqualTo(1);
		assertThat(envelope.producer()).isEqualTo("test-service");
		assertThat(envelope.aggregateId()).isEqualTo(aggregateId);
		assertThat(envelope.aggregateVersion()).isEqualTo(7);
		assertThat(envelope.correlationId()).isEqualTo(correlationId);
		assertThat(envelope.actorId()).isEqualTo(actorId);
		assertThat(envelope.occurredAt()).isNotNull();
		assertThat(envelope.payload().get("k").asString()).isEqualTo("v");
	}

	@Test
	void purgeRemovesOnlyOldPublishedRows() {
		jdbc.update("insert into outbox(id, exchange, routing_key, envelope, created_at, published_at) values (?, 'x', 'y', '{}'::jsonb, now() - interval '8 days', now() - interval '8 days')", UUID.randomUUID());
		jdbc.update("insert into outbox(id, exchange, routing_key, envelope, created_at, published_at) values (?, 'x', 'y', '{}'::jsonb, now(), now())", UUID.randomUUID());
		jdbc.update("insert into outbox(id, exchange, routing_key, envelope, created_at) values (?, 'x', 'y', '{}'::jsonb, now() - interval '8 days')", UUID.randomUUID());

		jdbc.update("insert into idempotency_keys(key, principal, status, created_at) values ('old', ?, 202, now() - interval '25 hours')", UUID.randomUUID());

		purgeJob.purge();

		assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where key = 'old'", Integer.class)).isZero();
	}

	private int unpublished() {
		return jdbc.queryForObject("select count(*) from outbox where published_at is null", Integer.class);
	}

	private List<String> drain() {
		List<String> ids = new ArrayList<>();
		Message m;
		while ((m = rabbit.receive(QUEUE, 500)) != null) {
			ids.add(m.getMessageProperties().getMessageId());
		}
		return ids;
	}
}
