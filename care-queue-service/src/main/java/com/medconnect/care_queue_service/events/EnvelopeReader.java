package com.medconnect.care_queue_service.events;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import com.medconnect.common.messaging.EventEnvelope;

import tools.jackson.databind.json.JsonMapper;

/** Parses the message body; a malformed body throws, so it goes through the tiered retries to the DLQ. */
@Component
class EnvelopeReader {

	private final JsonMapper json;

	EnvelopeReader(JsonMapper json) {
		this.json = json;
	}

	EventEnvelope read(Message message) {
		return json.readValue(message.getBody(), EventEnvelope.class);
	}
}
