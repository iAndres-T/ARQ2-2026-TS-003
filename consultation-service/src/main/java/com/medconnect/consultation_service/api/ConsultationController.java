package com.medconnect.consultation_service.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.common.security.CurrentUser;
import com.medconnect.common.web.IdempotencyService;
import com.medconnect.consultation_service.api.dto.CancelRequest;
import com.medconnect.consultation_service.api.dto.ConsultationView;
import com.medconnect.consultation_service.api.dto.Responses;
import com.medconnect.consultation_service.app.ConsultationService;
import com.medconnect.consultation_service.domain.Consultation;
import com.medconnect.consultation_service.domain.SymptomReport;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/consultations")
class ConsultationController {

	private final ConsultationService service;
	private final IdempotencyService idempotency;

	ConsultationController(ConsultationService service, IdempotencyService idempotency) {
		this.service = service;
		this.idempotency = idempotency;
	}

	@PostMapping
	ResponseEntity<?> create(@RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
			@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SymptomReport body) {
		UUID patientId = CurrentUser.from(jwt).sub();
		return idempotency.execute(key, patientId, () -> {
			Consultation c = service.create(patientId, body);
			return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(new Responses.Accepted(c.id(), c.status(), c.requestedAt()));
		});
	}

	@GetMapping("/{id}")
	ConsultationView get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		ConsultationView view = service.get(id);
		CurrentUser user = CurrentUser.from(jwt);
		// Ownership runs on every request, including cache hits.
		boolean allowed = user.isPatient() ? view.patientId().equals(user.sub())
				: user.isStaff() && user.doctorId() != null && user.doctorId().equals(view.doctorId());
		if (!allowed) {
			throw ConsultationService.forbidden();
		}
		// the cached joinUrl is the patient's; the doctor gets theirs from POST /start
		return (user.isPatient() || view.videoSession() == null) ? view : view.withVideoSession(
				new ConsultationView.VideoSession(view.videoSession().sessionId(), null));
	}

	@PutMapping("/{id}/symptoms")
	ResponseEntity<Responses.StatusOnly> updateSymptoms(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody SymptomReport body) {
		ConsultationView v = service.updateSymptoms(id, CurrentUser.from(jwt).sub(), body);
		return ResponseEntity.accepted().body(new Responses.StatusOnly(v.consultationId(), v.status()));
	}

	@PostMapping("/{id}/cancel")
	Responses.StatusOnly cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody(required = false) CancelRequest body) {
		Consultation c = service.cancel(id, CurrentUser.from(jwt).sub(), body == null ? null : body.reason());
		return new Responses.StatusOnly(c.id(), c.status());
	}
}
