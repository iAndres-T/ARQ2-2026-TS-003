package com.medconnect.common.messaging;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Event envelope shared by every MedConnect event (spec §3). */
public record EventEnvelope(
		UUID eventId,
		String eventType,
		int eventVersion,
		Instant occurredAt,
		String producer,
		UUID aggregateId,
		long aggregateVersion,
		UUID correlationId,
		UUID causationId,
		UUID actorId,
		JsonNode payload) {
}
