package com.medconnect.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
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
import org.springframework.amqp.core.BindingBuilder;

import com.medconnect.common.TestContainersConfig;

/** D8: a queue listed in medconnect.messaging.dead-letter-exchanges dead-letters to that exchange, not the default one. */
@SpringBootTest(properties = "medconnect.messaging.dead-letter-exchanges[test.dlx-failing]=test.dlx.fanout")
@Import({ TestContainersConfig.class, DeadLetterExchangeIT.FailingConsumer.class })
class DeadLetterExchangeIT {

	static final String QUEUE = "test.dlx-failing";

	@Autowired RabbitTemplate rabbit;

	@Test
	void finalFailureGoesToConfiguredExchange() {
		rabbit.convertAndSend("medconnect.events", "test.dlx-fail", "{}");

		Message viaFanout = null;
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (viaFanout == null && System.nanoTime() < deadline) {
			viaFanout = rabbit.receive("test.dlx-observer", 500);
		}

		assertThat(viaFanout).as("dead message routed through the configured exchange").isNotNull();
		assertThat(rabbit.receive(QUEUE + ".dlq", 300)).as("default-exchange DLQ stays empty").isNull();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FailingConsumer {

		@Bean
		Declarables dlxTopology() {
			FanoutExchange fanout = new FanoutExchange("test.dlx.fanout");
			Queue observer = new Queue("test.dlx-observer", true);
			var all = new java.util.ArrayList<>(ConsumerTopology.declarables(QUEUE, "medconnect.events", "test.dlx-fail")
				.getDeclarables());
			all.add(fanout);
			all.add(observer);
			all.add(BindingBuilder.bind(observer).to(fanout));
			return new Declarables(all);
		}

		@RabbitListener(queues = QUEUE)
		void onMessage(Message message) {
			throw new IllegalStateException("always fails");
		}
	}
}
