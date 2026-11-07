package com.medconnect.consultation_service.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.medconnect.consultation_service.domain.ConsultationStatus;

public final class Responses {

	private Responses() {
	}

	public record Accepted(UUID consultationId, ConsultationStatus status, Instant requestedAt) {
	}

	public record StatusOnly(UUID consultationId, ConsultationStatus status) {
	}
}
