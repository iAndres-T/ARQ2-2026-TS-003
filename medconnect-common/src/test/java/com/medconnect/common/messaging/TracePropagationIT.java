package com.medconnect.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import com.medconnect.common.Await;
import com.medconnect.common.TestContainersConfig;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/** D12: the consumer span continues the trace of the request that wrote the outbox row. */
@SpringBootTest
@Import({ TestContainersConfig.class, TracePropagationIT.Probe.class })
class TracePropagationIT {

	static final AtomicReference<String> consumerTraceId = new AtomicReference<>();

	@Autowired OutboxWriter writer;
	@Autowired OutboxRelay relay;
	@Autowired TransactionTemplate tx;
	@Autowired Tracer tracer;

	@Test
	void consumerSpanHasSameTraceIdAsProducerRequest() throws Exception {
		Span request = tracer.nextSpan().name("POST /test").start();
		String producerTraceId = request.context().traceId();
		try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
			tx.executeWithoutResult(s -> writer.append("medconnect.events", "trace.created", "TraceCreated",
					UUID.randomUUID(), 1, null, Map.of()));
		}
		finally {
			request.end();
		}

		relay.publishPending(); // runs on a thread without any span, like the real relay

		Await.until(Duration.ofSeconds(10), () -> consumerTraceId.get() != null);
		assertThat(consumerTraceId.get()).isEqualTo(producerTraceId);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Probe {

		@Bean
		Queue traceQueue() {
			return new Queue("test.trace");
		}

		@Bean
		Binding traceBinding(Queue traceQueue) {
			return BindingBuilder.bind(traceQueue).to(new TopicExchange("medconnect.events")).with("trace.#");
		}

		@Bean
		TraceListener traceListener(Tracer tracer) {
			return new TraceListener(tracer);
		}
	}

	static class TraceListener {

		private final Tracer tracer;

		TraceListener(Tracer tracer) {
			this.tracer = tracer;
		}

		@RabbitListener(queues = "test.trace")
		void on(String body) {
			Span span = tracer.currentSpan();
			consumerTraceId.set((span == null) ? "none" : span.context().traceId());
		}
	}
}
