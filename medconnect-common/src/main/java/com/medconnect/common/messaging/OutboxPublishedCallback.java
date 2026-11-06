package com.medconnect.common.messaging;

import java.util.UUID;

/** Invoked inside the relay transaction after the broker confirmed an outbox row (e.g. prescription PUBLISHED). */
@FunctionalInterface
public interface OutboxPublishedCallback {

	void published(UUID eventId, String eventType);
}
