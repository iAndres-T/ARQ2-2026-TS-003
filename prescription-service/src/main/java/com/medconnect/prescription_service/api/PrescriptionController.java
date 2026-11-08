package com.medconnect.prescription_service.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.common.security.CurrentUser;
import com.medconnect.common.web.ApiException;
import com.medconnect.common.web.IdempotencyService;
import com.medconnect.prescription_service.app.PrescriptionService;
import com.medconnect.prescription_service.domain.Prescription;
import com.medconnect.prescription_service.domain.PrescriptionItem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/prescriptions")
class PrescriptionController {

	private final PrescriptionService service;
	private final IdempotencyService idempotency;

	PrescriptionController(PrescriptionService service, IdempotencyService idempotency) {
		this.service = service;
		this.idempotency = idempotency;
	}

	record CreateRequest(@NotNull UUID consultationId, @NotEmpty @Size(max = 20) List<@Valid @NotNull PrescriptionItem> items,
			@Size(max = 1000) String notes) {
	}

	record Accepted(UUID prescriptionId, UUID consultationId, String deliveryStatus, Instant issuedAt) {
	}

	@PostMapping
	ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
			@Valid @RequestBody CreateRequest body) {
		CurrentUser user = CurrentUser.from(jwt);
		if (user.doctorId() == null) {
			throw new ApiException(HttpStatus.FORBIDDEN, "Not a doctor", "The token carries no doctorId");
		}
		return idempotency.execute(key, user.sub(), () -> {
			Prescription p = service.issue(user, body.consultationId(), body.items(), body.notes());
			return ResponseEntity.accepted().body(new Accepted(p.prescriptionId(), p.consultationId(), p.deliveryStatus(), p.issuedAt()));
		});
	}

	@GetMapping("/{id}")
	Prescription get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return service.get(CurrentUser.from(jwt), id);
	}
}
