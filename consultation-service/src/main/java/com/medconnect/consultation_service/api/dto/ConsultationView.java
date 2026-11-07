package com.medconnect.consultation_service.api.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import com.medconnect.consultation_service.domain.ConsultationStatus;
import com.medconnect.consultation_service.domain.Priority;
import com.medconnect.consultation_service.domain.SymptomReport;

/** Spec §1 Consultation. Serializable because it is the value cached in Redis (D11). */
public record ConsultationView(
		UUID consultationId,
		UUID patientId,
		ConsultationStatus status,
		Priority currentPriority,
		SymptomReport symptoms,
		UUID doctorId,
		VideoSession videoSession,
		Instant requestedAt,
		Instant assignedAt,
		Instant startedAt,
		Instant completedAt,
		long version) implements Serializable {

	public ConsultationView withVideoSession(VideoSession session) {
		return new ConsultationView(consultationId, patientId, status, currentPriority, symptoms, doctorId, session,
				requestedAt, assignedAt, startedAt, completedAt, version);
	}

	public record VideoSession(String sessionId, String joinUrl) implements Serializable {
	}
}
