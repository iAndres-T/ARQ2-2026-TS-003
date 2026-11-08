package com.medconnect.prescription_service.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Spec §1 Prescription. {@code eventId} (outbox id of PrescriptionIssued) is internal. */
public record Prescription(
		UUID prescriptionId,
		UUID consultationId,
		UUID patientId,
		UUID doctorId,
		List<PrescriptionItem> items,
		String notes,
		String deliveryStatus,
		Instant issuedAt,
		@JsonIgnore UUID eventId) {
}
