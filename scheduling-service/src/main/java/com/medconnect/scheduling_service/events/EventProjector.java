package com.medconnect.scheduling_service.events;

import java.util.function.Consumer;

import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.medconnect.common.messaging.EventEnvelope;
import com.medconnect.common.messaging.InboxGuard;
import com.medconnect.common.messaging.OutboxWriter;

import tools.jackson.databind.json.JsonMapper;

/** Parses the envelope, deduplicates by eventId and runs the handler in one transaction with causation MDC set. */
@Component
class EventProjector {

	private final JsonMapper json;
	private final InboxGuard inbox;

	EventProjector(JsonMapper json, InboxGuard inbox) {
		this.json = json;
		this.inbox = inbox;
	}

	@Transactional
	void handle(Message message, String queue, Consumer<EventEnvelope> handler) {
		EventEnvelope envelope = json.readValue(message.getBody(), EventEnvelope.class);
		MDC.put(OutboxWriter.CAUSATION_ID_MDC, String.valueOf(envelope.eventId()));
		MDC.put(OutboxWriter.CORRELATION_ID_MDC, String.valueOf(envelope.correlationId()));
		try {
			if (inbox.firstTime(envelope.eventId(), queue)) {
				handler.accept(envelope);
			}
		}
		finally {
			MDC.remove(OutboxWriter.CAUSATION_ID_MDC);
			MDC.remove(OutboxWriter.CORRELATION_ID_MDC);
		}
	}
}
