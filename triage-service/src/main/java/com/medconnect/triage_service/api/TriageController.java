package com.medconnect.triage_service.api;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.common.security.CurrentUser;
import com.medconnect.common.web.ApiException;
import com.medconnect.triage_service.app.TriageService;
import com.medconnect.triage_service.app.TriageService.OverrideResult;
import com.medconnect.triage_service.domain.Priority;
import com.medconnect.triage_service.domain.TriageRecord;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/triage/consultations")
class TriageController {

	private final TriageService service;

	TriageController(TriageService service) {
		this.service = service;
	}

	record OverrideRequest(@NotNull Priority newPriority, @NotNull @Size(min = 5, max = 500) String reason) {
	}

	record StaffView(UUID consultationId, Priority priority, TriageRecord.Source source, String ruleId,
			String ruleSetVersion, int triageVersion, UUID overriddenBy, String reason, Instant assessedAt) {
	}

	/** Patients never see ruleId, reason or overriddenBy. */
	record PatientView(UUID consultationId, Priority priority, TriageRecord.Source source, String ruleSetVersion,
			int triageVersion, Instant assessedAt) {
	}

	@PostMapping("/{id}/override")
	OverrideResult override(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OverrideRequest body) {
		return service.override(id, body.newPriority(), body.reason(), CurrentUser.from(jwt).sub());
	}

	@GetMapping("/{id}")
	Object get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		TriageRecord r = service.get(id);
		CurrentUser user = CurrentUser.from(jwt);
		if (user.isStaff()) {
			return new StaffView(r.consultationId(), r.priority(), r.source(), r.ruleId(), r.ruleSetVersion(),
					r.triageVersion(), r.overriddenBy(), r.reason(), r.assessedAt());
		}
		if (!user.isPatient() || !user.sub().equals(r.patientId())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden", "Not the owner of this consultation");
		}
		return new PatientView(r.consultationId(), r.priority(), r.source(), r.ruleSetVersion(), r.triageVersion(),
				r.assessedAt());
	}
}
