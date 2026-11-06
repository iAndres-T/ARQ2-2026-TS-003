package com.medconnect.common.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.rabbitmq.client.ReturnCallback;
import com.rabbitmq.client.ReturnListener;

/**
 * Publishes committed outbox rows to RabbitMQ with publisher confirms (ADR-002). Rows stay locked
 * ({@code FOR UPDATE SKIP LOCKED}) until confirmed, so parallel relays never publish the same row twice. Messages
 * are mandatory: a row the broker cannot route (no queue bound yet) stays unpublished and is retried with backoff.
 * Runs on its own thread so slow service jobs on the shared scheduler cannot starve it.
 */
public class OutboxRelay implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
	private static final int BATCH_SIZE = 100;
	private static final int MAX_BATCHES_PER_RUN = 20;
	private static final long CONFIRM_TIMEOUT_MS = 10_000;

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final RabbitTemplate rabbit;
	private final List<OutboxPublishedCallback> callbacks;
	private final Duration pollInterval;
	private final DefaultMessagePropertiesConverter propertiesConverter = new DefaultMessagePropertiesConverter();
	private volatile ScheduledExecutorService executor;

	public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, RabbitTemplate rabbit,
			List<OutboxPublishedCallback> callbacks, Duration pollInterval) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.rabbit = rabbit;
		this.callbacks = callbacks;
		this.pollInterval = pollInterval;
	}

	/** Drains while batches come back full (bounded per run) so a burst is not capped at BATCH_SIZE per poll. */
	public void publishPending() {
		for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
			Integer fetched = tx.execute(status -> publishBatch());
			if (fetched == null || fetched < BATCH_SIZE) {
				return;
			}
		}
	}

	private int publishBatch() {
		List<Row> rows = jdbc.query("""
				select id, exchange, routing_key, envelope::text as envelope, traceparent,
				       envelope->>'eventType' as event_type, envelope->>'correlationId' as correlation_id
				from outbox
				where published_at is null and (next_attempt_at is null or next_attempt_at <= now())
				order by created_at
				limit ? for update skip locked""",
				(rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("exchange"),
						rs.getString("routing_key"), rs.getString("envelope"), rs.getString("traceparent"),
						rs.getString("event_type"), rs.getString("correlation_id")),
				BATCH_SIZE);
		if (rows.isEmpty()) {
			return 0;
		}
		Set<String> returned = publish(rows);
		List<Row> published = rows.stream().filter(row -> !returned.contains(row.id().toString())).toList();
		List<Row> unroutable = rows.stream().filter(row -> returned.contains(row.id().toString())).toList();
		jdbc.batchUpdate("update outbox set published_at = now() where id = ?",
				published.stream().map(row -> new Object[] { row.id() }).toList());
		jdbc.batchUpdate("""
				update outbox set attempts = attempts + 1, last_error = 'unroutable',
				       next_attempt_at = now() + least(power(2, attempts), 300) * interval '1 second'
				where id = ?""", unroutable.stream().map(row -> new Object[] { row.id() }).toList());
		unroutable.forEach(row -> log.warn("Outbox event {} ({}) is unroutable on {}/{}; will retry", row.id(),
				row.eventType(), row.exchange(), row.routingKey()));
		published.forEach(row -> callbacks.forEach(cb -> cb.published(row.id(), row.eventType())));
		return rows.size();
	}

	/** @return message ids the broker returned as unroutable; returns arrive before the confirm */
	private Set<String> publish(List<Row> rows) {
		return rabbit.execute(channel -> {
			Set<String> returned = ConcurrentHashMap.newKeySet();
			ReturnListener listener = channel.addReturnListener((ReturnCallback) r -> returned.add(r.getProperties().getMessageId()));
			try {
				for (Row row : rows) {
					channel.basicPublish(row.exchange(), row.routingKey(), true,
							propertiesConverter.fromMessageProperties(properties(row), StandardCharsets.UTF_8.name()),
							row.envelope().getBytes(StandardCharsets.UTF_8));
				}
				channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
				return returned;
			}
			finally {
				channel.removeReturnListener(listener);
			}
		});
	}

	private static MessageProperties properties(Row row) {
		MessageProperties props = new MessageProperties();
		props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		props.setContentEncoding(StandardCharsets.UTF_8.name());
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		props.setMessageId(row.id().toString());
		props.setType(row.eventType());
		props.setCorrelationId(row.correlationId());
		if (row.traceparent() != null) {
			props.setHeader("traceparent", row.traceparent());
		}
		return props;
	}

	private void publishSafely() {
		try {
			publishPending();
		}
		catch (RuntimeException ex) {
			log.warn("Outbox relay run failed; rows stay pending and are retried", ex);
		}
	}

	@Override
	public void start() {
		executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "outbox-relay"));
		executor.scheduleWithFixedDelay(this::publishSafely, pollInterval.toMillis(), pollInterval.toMillis(),
				TimeUnit.MILLISECONDS);
	}

	@Override
	public void stop() {
		ScheduledExecutorService current = executor;
		executor = null;
		if (current != null) {
			current.shutdown();
		}
	}

	@Override
	public boolean isRunning() {
		return executor != null;
	}

	private record Row(UUID id, String exchange, String routingKey, String envelope, String traceparent,
			String eventType, String correlationId) {
	}
}
