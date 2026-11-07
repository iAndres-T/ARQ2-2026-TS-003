package com.medconnect.consultation_service.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.common.security.CurrentUser;
import com.medconnect.consultation_service.app.ConsultationService;
import com.medconnect.consultation_service.domain.Consultation;
import com.medconnect.consultation_service.domain.ConsultationStatus;
import com.medconnect.consultation_service.video.VideoProviderClient;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Spec §2 join / start / complete. Roles are enforced in SecurityConfig, ownership in the service. */
@RestController
@RequestMapping("/api/v1/consultations/{id}")
class LifecycleController {

	private final ConsultationService service;
	private final VideoProviderClient video;

	LifecycleController(ConsultationService service, VideoProviderClient video) {
		this.service = service;
		this.video = video;
	}

	@PostMapping("/join")
	Joined join(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		Consultation c = service.join(id, CurrentUser.from(jwt).sub());
		return new Joined(c.id(), c.sessionId(), c.patientJoinUrl());
	}

	@PostMapping("/start")
	Started start(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		Consultation c = service.start(id, CurrentUser.from(jwt).doctorId());
		return new Started(c.id(), c.status(), c.doctorJoinUrl());
	}

	@PostMapping("/complete")
	Completed complete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CompleteRequest body) {
		Consultation c = service.complete(id, CurrentUser.from(jwt).doctorId(), body.clinicalSummary(), body.diagnosisCodes());
		video.endSession(c.sessionId()); // after commit, best effort
		return new Completed(c.id(), c.status(), c.completedAt());
	}

	record CompleteRequest(@NotBlank @Size(max = 5000) String clinicalSummary,
			@NotNull @Size(max = 20) List<@NotBlank String> diagnosisCodes) {
	}

	record Joined(UUID consultationId, String sessionId, String joinUrl) {
	}

	record Started(UUID consultationId, ConsultationStatus status, String joinUrl) {
	}

	record Completed(UUID consultationId, ConsultationStatus status, Instant completedAt) {
	}
}
