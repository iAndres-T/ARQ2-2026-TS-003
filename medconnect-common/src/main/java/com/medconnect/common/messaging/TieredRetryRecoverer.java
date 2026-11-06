package com.medconnect.common.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.core.NestedExceptionUtils;

/**
 * Failed delivery → republish to the next retry tier (5 s, 30 s, 2 min, 10 min, 1 h); after the last tier the
 * message goes to {@code <queue>.dlq}. Attempt 1 is the original delivery, so a message is tried at most
 * {@code delays + 1} times.
 */
public class TieredRetryRecoverer implements MessageRecoverer {

	public static final String ATTEMPT_HEADER = "x-attempt";
	public static final String FINAL_ATTEMPTS_HEADER = "x-final-attempts";
	public static final String EXCEPTION_HEADER = "x-exception-message";

	private static final Logger log = LoggerFactory.getLogger(TieredRetryRecoverer.class);
	private static final long CONFIRM_TIMEOUT_MS = 10_000;

	private final RabbitTemplate rabbit;
	private final List<Duration> delays;
	private final Map<String, String> deadLetterExchanges;

	public TieredRetryRecoverer(RabbitTemplate rabbit, List<Duration> delays, Map<String, String> deadLetterExchanges) {
		this.rabbit = rabbit;
		this.delays = List.copyOf(delays);
		this.deadLetterExchanges = Map.copyOf(deadLetterExchanges);
	}

	@Override
	public void recover(Message message, Throwable cause) {
		MessageProperties props = message.getMessageProperties();
		String queue = props.getConsumerQueue();
		Integer header = props.getHeader(ATTEMPT_HEADER);
		int attempt = (header == null) ? 1 : header;
		props.getHeaders().remove("x-death");
		// received properties carry no delivery mode; without this the copy would be transient (spec §3: persistent)
		props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		if (attempt <= delays.size()) {
			String tier = MessagingTopology.tierName(delays.get(attempt - 1));
			props.setHeader(ATTEMPT_HEADER, attempt + 1);
			props.setHeader(MessagingTopology.RETRY_TIER_HEADER, tier);
			log.warn("Delivery {} of message {} on {} failed, retrying in {}", attempt, props.getMessageId(), queue, tier, cause);
			send(MessagingTopology.RETRY_EXCHANGE, queue, message);
		}
		else {
			props.setHeader(EXCEPTION_HEADER, NestedExceptionUtils.getMostSpecificCause(cause).getMessage());
			// a consumer of the DLQ/DLX (or an operator replay) must start with a fresh retry budget
			props.getHeaders().remove(ATTEMPT_HEADER);
			props.getHeaders().remove(MessagingTopology.RETRY_TIER_HEADER);
			props.setHeader(FINAL_ATTEMPTS_HEADER, attempt);
			log.error("Message {} on {} failed {} times, moving to DLQ", props.getMessageId(), queue, attempt, cause);
			send(deadLetterExchanges.getOrDefault(queue, ""), ConsumerTopology.dlq(queue), message);
		}
	}

	private void send(String exchange, String routingKey, Message message) {
		rabbit.invoke(ops -> {
			ops.send(exchange, routingKey, message);
			ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
			return null;
		});
	}
}
