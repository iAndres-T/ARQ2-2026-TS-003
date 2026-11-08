package com.medconnect.triage_service.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.medconnect.triage_service.rules.TriageDecision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Current triage state of a consultation; {@code triageVersion} is also the aggregateVersion of produced events. */
@Entity
@Table(name = "triage_records")
public class TriageRecord {

	public enum Source {
		AUTOMATIC, OVERRIDE
	}

	/** D7 projection: WAITING is not observable here, so TRIAGED means "triaged and not yet assigned/cancelled". */
	public enum Status {
		TRIAGED, ASSIGNED, CANCELLED
	}

	@Id
	@Column(name = "consultation_id")
	private UUID consultationId;
	@Column(name = "patient_id")
	private UUID patientId;
	@Column(name = "requested_at")
	private Instant requestedAt;
	@Enumerated(EnumType.STRING)
	private Priority priority;
	@Enumerated(EnumType.STRING)
	private Source source;
	@Column(name = "rule_id")
	private String ruleId;
	@Column(name = "rule_set_version")
	private String ruleSetVersion;
	@Column(name = "triage_version")
	private int triageVersion;
	@Column(name = "overridden_by")
	private UUID overriddenBy;
	private String reason;
	@Enumerated(EnumType.STRING)
	@Column(name = "status_projection")
	private Status statusProjection;
	@JdbcTypeCode(SqlTypes.JSON)
	private String symptoms;
	@Column(name = "last_symptoms_version")
	private long lastSymptomsVersion;
	@Column(name = "assessed_at")
	private Instant assessedAt;

	protected TriageRecord() {
	}

	public static TriageRecord initial(UUID consultationId, UUID patientId, Instant requestedAt, TriageDecision d,
			String ruleSetVersion, String symptomsJson, long symptomsVersion, Instant now) {
		TriageRecord r = new TriageRecord();
		r.consultationId = consultationId;
		r.patientId = patientId;
		r.requestedAt = requestedAt;
		r.statusProjection = Status.TRIAGED;
		r.retriage(d, ruleSetVersion, symptomsJson, symptomsVersion, now);
		return r;
	}

	/** Automatic (re-)triage; always bumps triageVersion. */
	public void retriage(TriageDecision d, String ruleSetVersion, String symptomsJson, long symptomsVersion, Instant now) {
		priority = d.priority();
		source = Source.AUTOMATIC;
		ruleId = d.ruleId();
		this.ruleSetVersion = ruleSetVersion;
		overriddenBy = null;
		reason = null;
		symptoms = symptomsJson;
		lastSymptomsVersion = symptomsVersion;
		triageVersion++;
		assessedAt = now;
	}

	public void override(Priority newPriority, UUID staffId, String reason, Instant now) {
		priority = newPriority;
		source = Source.OVERRIDE;
		ruleId = null;
		overriddenBy = staffId;
		this.reason = reason;
		triageVersion++;
		assessedAt = now;
	}

	public void projectStatus(Status status) {
		this.statusProjection = status;
	}

	public UUID consultationId() {
		return consultationId;
	}

	public UUID patientId() {
		return patientId;
	}

	public Instant requestedAt() {
		return requestedAt;
	}

	public Priority priority() {
		return priority;
	}

	public Source source() {
		return source;
	}

	public String ruleId() {
		return ruleId;
	}

	public String ruleSetVersion() {
		return ruleSetVersion;
	}

	public int triageVersion() {
		return triageVersion;
	}

	public UUID overriddenBy() {
		return overriddenBy;
	}

	public String reason() {
		return reason;
	}

	public Status statusProjection() {
		return statusProjection;
	}

	public long lastSymptomsVersion() {
		return lastSymptomsVersion;
	}

	public Instant assessedAt() {
		return assessedAt;
	}
}
