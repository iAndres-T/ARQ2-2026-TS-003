package com.medconnect.triage_service.app;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medconnect.common.messaging.MessagingTopology;
import com.medconnect.common.messaging.OutboxWriter;
import com.medconnect.common.web.ApiException;
import com.medconnect.triage_service.domain.Priority;
import com.medconnect.triage_service.domain.SymptomReport;
import com.medconnect.triage_service.domain.TriageRecord;
import com.medconnect.triage_service.domain.TriageRecord.Status;
import com.medconnect.triage_service.domain.TriageRecordRepository;
import com.medconnect.triage_service.rules.RuleSetV1;
import com.medconnect.triage_service.rules.TriageDecision;

import tools.jackson.databind.json.JsonMapper;

@Service
public class TriageService {

	private static final Logger log = LoggerFactory.getLogger(TriageService.class);

	private final TriageRecordRepository repo;
	private final OutboxWriter outbox;
	private final JsonMapper json;

	TriageService(TriageRecordRepository repo, OutboxWriter outbox, JsonMapper json) {
		this.repo = repo;
		this.outbox = outbox;
		this.json = json;
	}

	/** Initial triage; redelivery of an already-known consultation is a no-op. */
	@Transactional
	public void onConsultationRequested(UUID consultationId, UUID patientId, SymptomReport symptoms, Instant requestedAt,
			long aggregateVersion) {
		if (repo.existsById(consultationId)) {
			return;
		}
		Instant now = Instant.now();
		TriageDecision d = RuleSetV1.evaluate(symptoms);
		TriageRecord r = repo.save(TriageRecord.initial(consultationId, patientId, requestedAt, d, RuleSetV1.VERSION,
				json.writeValueAsString(symptoms), aggregateVersion, now));
		emitTriaged(r, symptoms, "INITIAL", now);
	}

	@Transactional
	public void onSymptomsUpdated(UUID consultationId, SymptomReport symptoms, long aggregateVersion) {
		// Cross-queue order is not guaranteed: an update seen before the request fails and goes through the tiered retry.
		TriageRecord r = repo.findForUpdate(consultationId)
			.orElseThrow(() -> new IllegalStateException("SymptomsUpdated before ConsultationRequested"));
		if (aggregateVersion <= r.lastSymptomsVersion() || r.statusProjection() != Status.TRIAGED) {
			return;
		}
		Instant now = Instant.now();
		r.retriage(RuleSetV1.evaluate(symptoms), RuleSetV1.VERSION, json.writeValueAsString(symptoms), aggregateVersion, now);
		emitTriaged(r, symptoms, "SYMPTOMS_UPDATED", now);
	}

	/** D7 status projection; unknown consultations are ignored. */
	@Transactional
	public void project(UUID consultationId, Status status) {
		repo.findForUpdate(consultationId).ifPresent(r -> r.projectStatus(status));
	}

	@Transactional
	public OverrideResult override(UUID consultationId, Priority newPriority, String reason, UUID staffId) {
		TriageRecord r = repo.findForUpdate(consultationId).orElseThrow(TriageService::notFound);
		if (r.statusProjection() != Status.TRIAGED) {
			throw new ApiException(HttpStatus.CONFLICT, "Conflict", "Consultation is not TRIAGED or WAITING");
		}
		if (r.priority() == newPriority) {
			throw new ApiException(HttpStatus.CONFLICT, "Conflict", "newPriority equals the current priority");
		}
		Priority previous = r.priority();
		Instant now = Instant.now();
		r.override(newPriority, staffId, reason, now);
		outbox.append(MessagingTopology.EVENTS_EXCHANGE, "triage.priority-overridden", "PriorityOverridden", consultationId,
				r.triageVersion(), staffId, new PriorityOverridden(consultationId, r.patientId(), previous, newPriority,
						r.triageVersion(), staffId, reason, r.requestedAt(), now));
		return new OverrideResult(consultationId, previous, newPriority, r.source(), r.triageVersion(), staffId, now);
	}

	@Transactional(readOnly = true)
	public TriageRecord get(UUID consultationId) {
		return repo.findById(consultationId).orElseThrow(TriageService::notFound);
	}

	private void emitTriaged(TriageRecord r, SymptomReport symptoms, String reason, Instant now) {
		outbox.append(MessagingTopology.EVENTS_EXCHANGE, "triage.patient-triaged", "PatientTriaged", r.consultationId(),
				r.triageVersion(), null, new PatientTriaged(r.consultationId(), r.patientId(), r.priority(), r.ruleId(),
						r.ruleSetVersion(), r.triageVersion(), reason, r.requestedAt(), now, symptoms));
		log.info("Consultation {} triaged priority={} ruleId={} triageVersion={}", r.consultationId(), r.priority(),
				r.ruleId(), r.triageVersion());
	}

	static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "Not found", "Consultation not found or not triaged yet");
	}

	public record OverrideResult(UUID consultationId, Priority previousPriority, Priority newPriority,
			TriageRecord.Source source, int triageVersion, UUID overriddenBy, Instant assessedAt) {
	}

	/** Spec section 3 PatientTriaged plus the D6 optional {@code symptoms}. */
	record PatientTriaged(UUID consultationId, UUID patientId, Priority priority, String ruleId, String ruleSetVersion,
			int triageVersion, String reason, Instant requestedAt, Instant triagedAt, SymptomReport symptoms) {
	}

	record PriorityOverridden(UUID consultationId, UUID patientId, Priority previousPriority, Priority newPriority,
			int triageVersion, UUID overriddenBy, String reason, Instant requestedAt, Instant overriddenAt) {
	}
}
