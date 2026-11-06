package com.medconnect.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.medconnect.common.Await;
import com.medconnect.common.TestContainersConfig;

/** A dead-lettered message must not carry the exhausted retry state: its next consumer gets a full retry budget. */
@SpringBootTest(properties = "medconnect.messaging.dead-letter-exchanges[test.fresh-failing]=test.fresh.fanout")
@Import({ TestContainersConfig.class, DeadLetterFreshRetryIT.Consumers.class })
class DeadLetterFreshRetryIT {

	@Autowired RabbitTemplate rabbit;
	@Autowired Consumers consumers;

	@Test
	void dlxConsumerFailingOnceStillSucceedsOnRetry() throws Exception {
		rabbit.convertAndSend("medconnect.events", "test.fresh-fail", "{}");

		Await.until(Duration.ofSeconds(30), () -> consumers.observerAttempts.size() >= 2);
		assertThat(consumers.observerAttempts).containsExactly(1, 2);
		assertThat(rabbit.receive("test.fresh-observer.dlq", 500)).as("observer DLQ stays empty").isNull();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Consumers {

		final List<Integer> observerAttempts = new CopyOnWriteArrayList<>();

		@Bean
		Declarables freshTopology() {
			List<Declarable> all = new ArrayList<>(ConsumerTopology.declarables("test.fresh-failing", "medconnect.events", "test.fresh-fail").getDeclarables());
			FanoutExchange fanout = new FanoutExchange("test.fresh.fanout");
			all.add(fanout);
			all.addAll(ConsumerTopology.declarables("test.fresh-observer", "medconnect.events", "test.fresh-unused").getDeclarables());
			all.add(BindingBuilder.bind(new Queue("test.fresh-observer", true)).to(fanout));
			return new Declarables(all);
		}

		@RabbitListener(queues = "test.fresh-failing")
		void failing(Message message) {
			throw new IllegalStateException("always fails");
		}

		@RabbitListener(queues = "test.fresh-observer")
		void observer(Message message) {
			Integer attempt = message.getMessageProperties().getHeader(TieredRetryRecoverer.ATTEMPT_HEADER);
			observerAttempts.add(attempt == null ? 1 : attempt);
			if (observerAttempts.size() == 1) {
				throw new IllegalStateException("transient");
			}
		}
	}
}
