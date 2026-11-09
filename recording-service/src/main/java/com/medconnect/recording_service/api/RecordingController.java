package com.medconnect.recording_service.api;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.common.web.ApiException;
import com.medconnect.recording_service.domain.Recording;
import com.medconnect.recording_service.domain.RecordingRepository;
import com.medconnect.recording_service.storage.RecordingStorage;

import com.fasterxml.jackson.annotation.JsonInclude;

@RestController
class RecordingController {

	private final RecordingRepository recordings;
	private final RecordingStorage storage;

	private final JdbcTemplate jdbc;

	RecordingController(RecordingRepository recordings, RecordingStorage storage, JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		this.recordings = recordings;
		this.storage = storage;
	}

	@JsonInclude(JsonInclude.Include.ALWAYS)
	record RecordingMetadata(UUID recordingId, UUID consultationId, String status, String url, Integer durationSeconds,
			Instant recordedAt, Long sizeBytes, String checksumSha256, String downloadUrl) {
	}

	/**
	 * Role + scope are enforced in SecurityConfig (R-F2-03); here the resource rule (ADR-003/005): the caller must be the
	 * consultation's doctor. Unknown consultation 404; known but not the caller's (or no doctor recorded) 403.
	 */
	@GetMapping("/api/v1/consultations/{consultationId}/recording")
	RecordingMetadata get(@PathVariable UUID consultationId, @AuthenticationPrincipal Jwt jwt) {
		var doctors = jdbc.queryForList("select doctor_id from session_map where consultation_id = ? and doctor_id is not null",
				UUID.class, consultationId);
		boolean known = !doctors.isEmpty() || recordings.findFirstByConsultationIdOrderByCreatedAtDesc(consultationId).isPresent()
				|| Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from session_map where consultation_id = ?)", Boolean.class, consultationId));
		if (!known) {
			throw new ApiException(HttpStatus.NOT_FOUND, "Recording not found", "No recording for that consultation");
		}
		String caller = jwt.getClaimAsString("doctorId");
		UUID callerId = null;
		try {
			callerId = caller == null ? null : UUID.fromString(caller);
		}
		catch (IllegalArgumentException ex) {
			// malformed claim: treated as no doctor
		}
		if (callerId == null || !doctors.contains(callerId)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden", "Not assigned to this consultation");
		}
		Recording r = recordings.findFirstByConsultationIdOrderByCreatedAtDesc(consultationId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Recording not found", "No recording for that consultation"));
		boolean available = r.status.equals("AVAILABLE");
		return new RecordingMetadata(r.id, r.consultationId, r.status, r.url, r.durationSeconds, r.recordedAt, r.sizeBytes,
				r.checksumSha256, available ? storage.presign(r.url) : null);
	}
}
