package com.medconnect.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.medconnect.common.Await;
import com.medconnect.common.TestContainersConfig;

@SpringBootTest
@Import({ TestContainersConfig.class, TieredRetryRecovererIT.FailingConsumer.class })
class TieredRetryRecovererIT {

	static final String QUEUE = "test.failing";

	@Autowired RabbitTemplate rabbit;
	@Autowired FailingConsumer consumer;

	@Test
	void failingConsumerGetsFiveRetriesThenDlq() throws Exception {
		rabbit.convertAndSend("medconnect.events", "test.fail", "{}");

		Message dead = null;
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (dead == null && System.nanoTime() < deadline) {
			dead = rabbit.receive(QUEUE + ".dlq", 500);
		}

		assertThat(dead).as("message in DLQ").isNotNull();
		// initial delivery + 5 retries (5 s, 30 s, 2 min, 10 min, 1 h in production; shortened in tests)
		assertThat(consumer.attempts).containsExactly(1, 2, 3, 4, 5, 6);
		assertThat(dead.getMessageProperties().<String>getHeader("x-exception-message")).isEqualTo("always fails");
		// spec §3: messages are persistent, also after retry tiers and in the DLQ
		assertThat(dead.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
		Await.until(Duration.ofSeconds(1), () -> true);
		assertThat(rabbit.receive(QUEUE + ".dlq", 300)).isNull();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FailingConsumer {

		final List<Integer> attempts = new CopyOnWriteArrayList<>();

		@Bean
		Declarables failingTopology() {
			return ConsumerTopology.declarables(QUEUE, "medconnect.events", "test.fail");
		}

		@RabbitListener(queues = QUEUE)
		void onMessage(Message message) {
			Integer attempt = message.getMessageProperties().getHeader(TieredRetryRecoverer.ATTEMPT_HEADER);
			attempts.add(attempt == null ? 1 : attempt);
			throw new IllegalStateException("always fails");
		}
	}
}
