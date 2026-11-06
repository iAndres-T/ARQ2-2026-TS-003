package com.medconnect.common.messaging;

import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

/**
 * Per-consumer topology (spec §3): durable queue {@code <consumer>.<event>}, its DLQ {@code <queue>.dlq}, the
 * binding(s) to the source exchange and the binding that brings retried messages back from the retry tiers.
 * Expose the result as a {@code @Bean} so RabbitAdmin re-declares it after a broker restart.
 */
public final class ConsumerTopology {

	private ConsumerTopology() {
	}

	public static Declarables declarables(String queue, String exchange, String... routingKeys) {
		Queue main = new Queue(queue, true);
		List<Declarable> declarables = new ArrayList<>();
		declarables.add(main);
		declarables.add(new Queue(dlq(queue), true));
		TopicExchange source = new TopicExchange(exchange);
		for (String routingKey : routingKeys) {
			declarables.add(BindingBuilder.bind(main).to(source).with(routingKey));
		}
		Binding retryReturn = BindingBuilder.bind(main).to(new DirectExchange(MessagingTopology.RETRY_RETURN_EXCHANGE)).with(queue);
		declarables.add(retryReturn);
		return new Declarables(declarables);
	}

	public static String dlq(String queue) {
		return queue + ".dlq";
	}
}
