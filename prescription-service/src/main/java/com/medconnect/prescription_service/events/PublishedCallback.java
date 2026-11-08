package com.medconnect.prescription_service.events;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.medconnect.common.messaging.OutboxPublishedCallback;
import com.medconnect.prescription_service.app.PrescriptionService;

/** Runs in the relay transaction once the broker confirmed PrescriptionIssued: deliveryStatus PENDING to PUBLISHED. */
@Component
class PublishedCallback implements OutboxPublishedCallback {

	private final PrescriptionService service;

	PublishedCallback(PrescriptionService service) {
		this.service = service;
	}

	@Override
	public void published(UUID eventId, String eventType) {
		if ("PrescriptionIssued".equals(eventType)) {
			service.markPublished(eventId);
		}
	}
}
