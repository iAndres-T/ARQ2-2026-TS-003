package com.medconnect.prescription_service.domain;

import java.util.UUID;

/** Projection of who is assigned to a consultation and whether it is still running. */
public record ConsultationAccess(UUID consultationId, UUID patientId, UUID doctorId, String status) {

	public static final String IN_PROGRESS = "IN_PROGRESS";
	public static final String COMPLETED = "COMPLETED";
}
