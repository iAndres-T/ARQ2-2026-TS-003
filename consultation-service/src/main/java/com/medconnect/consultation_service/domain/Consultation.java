package com.medconnect.consultation_service.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Consultation aggregate. {@code version} is the aggregate version carried by every event envelope: it is bumped
 * manually once per change and writers hold a row lock (see {@link ConsultationRepository#findForUpdate}).
 */
@Entity
@Table(name = "consultations")
public class Consultation {

	@Id
	private UUID id;
	@Column(name = "patient_id")
	private UUID patientId;
	@Enumerated(EnumType.STRING)
	private ConsultationStatus status;
	@Enumerated(EnumType.STRING)
	@Column(name = "current_priority")
	private Priority currentPriority;
	@Column(name = "triage_version")
	private int triageVersion;
	private String symptoms;
	@Column(name = "doctor_id")
	private UUID doctorId;
	@Column(name = "requested_at")
	private Instant requestedAt;
	@Column(name = "assigned_at")
	private Instant assignedAt;
	@Column(name = "started_at")
	private Instant startedAt;
	@Column(name = "completed_at")
	private Instant completedAt;
	private long version;
	@Column(name = "session_id")
	private String sessionId;
	@Column(name = "patient_join_url")
	private String patientJoinUrl;
	@Column(name = "doctor_join_url")
	private String doctorJoinUrl;
	@Column(name = "joined_at")
	private Instant joinedAt;
	/** When ASSIGNED was committed here; the no-show window starts from it (assignedAt is care-queue's claim time). */
	@Column(name = "assigned_committed_at")
	private Instant assignedCommittedAt;

	protected Consultation() {
	}

	public static Consultation request(UUID patientId, String symptomsJson, Instant requestedAt) {
		Consultation c = new Consultation();
		c.id = UUID.randomUUID();
		c.patientId = patientId;
		c.status = ConsultationStatus.REQUESTED;
		c.symptoms = symptomsJson;
		c.requestedAt = requestedAt;
		c.version = 1;
		return c;
	}

	/** Moves to {@code target} only if it is later on the forward path; terminal consultations never change. */
	public boolean applyForward(ConsultationStatus target) {
		if (status.isTerminal() || target.isTerminal() || target.ordinal() <= status.ordinal()) {
			return false;
		}
		status = target;
		return true;
	}

	/** Applies a triage result only if its triageVersion is newer than the stored one. */
	public boolean applyTriage(Priority priority, int newTriageVersion) {
		if (newTriageVersion <= triageVersion) {
			return false;
		}
		currentPriority = priority;
		triageVersion = newTriageVersion;
		return true;
	}

	/** Queue-driven priority: accepted when not older than the stored triageVersion. */
	public boolean applyQueuePriority(Priority priority, int newTriageVersion) {
		if (newTriageVersion < triageVersion || (newTriageVersion == triageVersion && priority == currentPriority)) {
			return false;
		}
		currentPriority = priority;
		triageVersion = newTriageVersion;
		return true;
	}

	public void replaceSymptoms(String symptomsJson) {
		symptoms = symptomsJson;
	}

	public void cancel() {
		if (!ConsultationStatus.canTransition(status, ConsultationStatus.CANCELLED)) {
			throw new IllegalStateException("Cannot cancel from " + status);
		}
		status = ConsultationStatus.CANCELLED;
	}

	/** PatientAssigned: forward-only to ASSIGNED (from WAITING, or earlier if WAITING was skipped by reordering). */
	public boolean assign(UUID doctorId, Instant assignedAt) {
		if (!applyForward(ConsultationStatus.ASSIGNED)) {
			return false;
		}
		this.doctorId = doctorId;
		this.assignedAt = assignedAt;
		this.assignedCommittedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		return true;
	}

	/** Stores the lazily created provider session (first join/start). */
	public void attachSession(String sessionId, String patientJoinUrl, String doctorJoinUrl) {
		this.sessionId = sessionId;
		this.patientJoinUrl = patientJoinUrl;
		this.doctorJoinUrl = doctorJoinUrl;
	}

	/** Marks the patient present (prevents NO_SHOW); false when already joined. */
	public boolean join(Instant now) {
		if (joinedAt != null) {
			return false;
		}
		joinedAt = now;
		return true;
	}

	public void start(Instant now) {
		status = ConsultationStatus.IN_PROGRESS;
		startedAt = now;
	}

	public void complete(Instant now) {
		status = ConsultationStatus.COMPLETED;
		completedAt = now;
	}

	public void noShow() {
		status = ConsultationStatus.NO_SHOW;
	}

	public void bump() {
		version++;
	}

	public UUID id() { return id; }
	public UUID patientId() { return patientId; }
	public ConsultationStatus status() { return status; }
	public Priority currentPriority() { return currentPriority; }
	public int triageVersion() { return triageVersion; }
	public String symptoms() { return symptoms; }
	public UUID doctorId() { return doctorId; }
	public Instant requestedAt() { return requestedAt; }
	public Instant assignedAt() { return assignedAt; }
	public Instant startedAt() { return startedAt; }
	public Instant completedAt() { return completedAt; }
	public long version() { return version; }
	public String sessionId() { return sessionId; }
	public String patientJoinUrl() { return patientJoinUrl; }
	public String doctorJoinUrl() { return doctorJoinUrl; }
	public Instant joinedAt() { return joinedAt; }
	public Instant assignedCommittedAt() { return assignedCommittedAt; }
}
