package com.medconnect.common.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.HeadersExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/** Platform-wide exchanges and retry tiers (spec §3, plan D4). Mirrors infra/rabbitmq/definitions.json. */
public final class MessagingTopology {

	public static final String EVENTS_EXCHANGE = "medconnect.events";
	public static final String PHARMACY_EXCHANGE = "medconnect.pharmacy";
	public static final String PHARMACY_DLX_EXCHANGE = "medconnect.pharmacy.dlx";
	public static final String RETRY_EXCHANGE = "medconnect.retry";
	public static final String RETRY_RETURN_EXCHANGE = "medconnect.retry.return";
	public static final String RETRY_TIER_HEADER = "retry-tier";

	private MessagingTopology() {
	}

	static Declarables declarables(List<Duration> retryDelays) {
		List<Declarable> declarables = new ArrayList<>(List.of(new TopicExchange(EVENTS_EXCHANGE),
				new TopicExchange(PHARMACY_EXCHANGE), new FanoutExchange(PHARMACY_DLX_EXCHANGE),
				new DirectExchange(RETRY_RETURN_EXCHANGE)));
		HeadersExchange retry = new HeadersExchange(RETRY_EXCHANGE);
		declarables.add(retry);
		for (Duration delay : retryDelays) {
			String tier = tierName(delay);
			Queue queue = QueueBuilder.durable("retry." + tier)
				.ttl((int) delay.toMillis())
				.deadLetterExchange(RETRY_RETURN_EXCHANGE)
				.build();
			declarables.add(queue);
			declarables.add(BindingBuilder.bind(queue).to(retry).whereAll(Map.of(RETRY_TIER_HEADER, tier)).match());
		}
		return new Declarables(declarables);
	}

	/** 5s, 30s, 2m, 10m, 1h, or milliseconds ("150ms") when not a whole second. */
	static String tierName(Duration delay) {
		long ms = delay.toMillis();
		if (ms % 3_600_000 == 0) {
			return (ms / 3_600_000) + "h";
		}
		if (ms % 60_000 == 0) {
			return (ms / 60_000) + "m";
		}
		if (ms % 1_000 == 0) {
			return (ms / 1_000) + "s";
		}
		return ms + "ms";
	}
}
