package com.medconnect.consultation_service.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.medconnect.common.messaging.MessagingTopology;
import com.medconnect.common.messaging.OutboxWriter;
import com.medconnect.common.web.ApiException;
import com.medconnect.consultation_service.api.dto.ConsultationView;
import com.medconnect.consultation_service.domain.Consultation;
import com.medconnect.consultation_service.domain.ConsultationRepository;
import com.medconnect.consultation_service.domain.ConsultationStatus;
import com.medconnect.consultation_service.domain.Priority;
import com.medconnect.consultation_service.domain.SymptomReport;
import com.medconnect.consultation_service.video.VideoProviderClient;
import com.medconnect.consultation_service.video.VideoSession;

import tools.jackson.databind.json.JsonMapper;

@Service
public class ConsultationService {

	public static final String CACHE = "consultations";
	private static final Logger log = LoggerFactory.getLogger(ConsultationService.class);

	private final ConsultationRepository repo;
	private final OutboxWriter outbox;
	private final JsonMapper json;
	private final JdbcTemplate jdbc;
	private final CacheManager caches;
	private final VideoProviderClient video;
	private final org.springframework.transaction.support.TransactionTemplate tx;

	public ConsultationService(ConsultationRepository repo, OutboxWriter outbox, JsonMapper json, JdbcTemplate jdbc,
			CacheManager caches, VideoProviderClient video,
			org.springframework.transaction.PlatformTransactionManager txm) {
		this.tx = new org.springframework.transaction.support.TransactionTemplate(txm);
		this.video = video;
		this.repo = repo;
		this.outbox = outbox;
		this.json = json;
		this.jdbc = jdbc;
		this.caches = caches;
	}

	@Transactional
	public Consultation create(UUID patientId, SymptomReport symptoms) {
		// Serializes concurrent requests of the same patient so the 409 below is deterministic; the partial unique
		// index stays as the backstop.
		jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
		}, patientId.toString());
		if (repo.existsByPatientIdAndStatusIn(patientId, ConsultationStatus.ACTIVE)) {
			throw activeExists();
		}
		Consultation c = Consultation.request(patientId, json.writeValueAsString(symptoms),
				Instant.now().truncatedTo(ChronoUnit.MICROS));
		try {
			repo.saveAndFlush(c);
		}
		catch (DataIntegrityViolationException ex) {
			throw activeExists();
		}
		outbox.append(MessagingTopology.EVENTS_EXCHANGE, "consultation.requested", "ConsultationRequested", c.id(),
				c.version(), patientId,
				Map.of("consultationId", c.id(), "patientId", patientId, "symptoms", symptoms, "requestedAt", c.requestedAt()));
		log.info("Consultation {} requested (status {})", c.id(), c.status());
		return c;
	}

	/** Cached read (D11). Callers must still run the ownership check on every request. */
	public ConsultationView get(UUID id) {
		ConsultationView cached = null;
		try {
			cached = caches.getCache(CACHE).get(id.toString(), ConsultationView.class);
		}
		catch (RuntimeException ex) {
			log.warn("Cache read failed, falling back to the database: {}", ex.toString());
		}
		if (cached != null) {
			return cached;
		}
		ConsultationView view = repo.findById(id).map(this::toView).orElseThrow(ConsultationService::notFound);
		try {
			caches.getCache(CACHE).put(id.toString(), view);
		}
		catch (RuntimeException ex) {
			log.warn("Cache write failed: {}", ex.toString());
		}
		return view;
	}

	@Transactional
	public ConsultationView updateSymptoms(UUID id, UUID patientId, SymptomReport symptoms) {
		Consultation c = ownedForUpdate(id, patientId);
		if (c.status() != ConsultationStatus.TRIAGED && c.status() != ConsultationStatus.WAITING) {
			throw new ApiException(HttpStatus.CONFLICT, "Invalid state", "Symptoms can only be updated in TRIAGED or WAITING, not " + c.status());
		}
		c.replaceSymptoms(json.writeValueAsString(symptoms));
		c.bump();
		outbox.append(MessagingTopology.EVENTS_EXCHANGE, "consultation.symptoms-updated", "SymptomsUpdated", c.id(),
				c.version(), patientId,
				Map.of("consultationId", c.id(), "patientId", patientId, "symptoms", symptoms, "updatedAt", Instant.now()));
		evictAfterCommit(id);
		return toView(c);
	}

	@Transactional
	public Consultation cancel(UUID id, UUID patientId, String reason) {
		Consultation c = ownedForUpdate(id, patientId);
		ConsultationStatus before = c.status();
		if (!ConsultationStatus.canTransition(before, ConsultationStatus.CANCELLED)) {
			throw new ApiException(HttpStatus.CONFLICT, "Invalid state", "Cannot cancel a consultation in status " + before);
		}
		c.cancel();
		c.bump();
		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("consultationId", c.id());
		payload.put("patientId", patientId);
		payload.put("doctorId", before == ConsultationStatus.ASSIGNED ? c.doctorId() : null);
		payload.put("statusAtCancellation", before);
		payload.put("reason", reason);
		payload.put("cancelledAt", Instant.now());
		outbox.append(MessagingTopology.EVENTS_EXCHANGE, "consultation.cancelled", "ConsultationCancelled", c.id(),
				c.version(), patientId, payload);
		evictAfterCommit(id);
		return c;
	}

	// --- Lifecycle after assignment (spec §2 join/start/complete, R-F4-05/06) ---

	/**
	 * Forward-only to ASSIGNED; PatientAssigned.aggregateVersion is care-queue's triageVersion, so it is not compared.
	 * No provider call here (I1): the video session is created lazily on join/start, so an outage cannot strand the claim.
	 */
	@Transactional
	public void onPatientAssigned(UUID id, UUID doctorId, Instant assignedAt) {
		Consultation c = repo.findForUpdate(id).orElse(null);
		if (c == null || c.status().isTerminal()) {
			log.info("PatientAssigned ignored for consultation {} ({})", id, c == null ? "unknown" : c.status());
			return;
		}
		if (c.assign(doctorId, assignedAt)) {
			c.bump();
			evictAfterCommit(id);
		}
		else {
			log.info("PatientAssigned ignored for consultation {} in {}", id, c.status());
		}
	}

	/**
	 * Provider session for join/start, created OUTSIDE any transaction (no DB connection or row lock is held during the
	 * slow provider call; create-session is idempotent per consultation). Returns null when nothing is needed or the
	 * state does not allow the call (the locked section then answers 404/403/409). Provider failure -> 502 (join per
	 * spec; start is a documented deviation).
	 */
	private VideoSession prefetchSession(UUID id, UUID caller, boolean patient) {
		var rows = jdbc.queryForList(
				"select status, patient_id, doctor_id, session_id, joined_at from consultations where id = ?", id);
		if (rows.isEmpty()) {
			return null;
		}
		var r = rows.get(0);
		boolean allowed = "ASSIGNED".equals(r.get("status")) && r.get("session_id") == null
				&& (patient ? r.get("patient_id").equals(caller) : caller != null && caller.equals(r.get("doctor_id")) && r.get("joined_at") != null);
		if (!allowed) {
			return null;
		}
		try {
			return video.createSession(id);
		}
		catch (RuntimeException ex) {
			log.warn("Video provider failed for consultation {}: {}", id, ex.toString());
			throw new ApiException(HttpStatus.BAD_GATEWAY, "Video provider error", "Video provider unavailable, retry later");
		}
	}

	/** Attaches the prefetched session only if none is stored yet; returns true when the aggregate changed. */
	private boolean attachSession(Consultation c, VideoSession prefetched) {
		if (c.sessionId() != null) {
			return false;
		}
		if (prefetched == null) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "Video provider error", "No video session yet, retry");
		}
		c.attachSession(prefetched.sessionId(), prefetched.patientJoinUrl(), prefetched.doctorJoinUrl());
		return true;
	}

	private <T> T locked(VideoSession prefetched, java.util.function.Supplier<T> body) {
		try {
			return tx.execute(status -> body.get());
		}
		catch (ApiException ex) {
			if (prefetched != null) {
				log.info("Provider session {} left unused: {}", prefetched.sessionId(), ex.getMessage());
			}
			throw ex;
		}
	}

	public Consultation join(UUID id, UUID patientId) {
		VideoSession fetched = prefetchSession(id, patientId, true);
		return locked(fetched, () -> {
			Consultation c = ownedForUpdate(id, patientId);
			if (c.status() != ConsultationStatus.ASSIGNED) {
				throw new ApiException(HttpStatus.CONFLICT, "Invalid state", "Join requires ASSIGNED, not " + c.status());
			}
			boolean changed = attachSession(c, fetched);
			changed |= c.join(now());
			if (changed) {
				c.bump();
				evictAfterCommit(id);
			}
			return c;
		});
	}

	public Consultation start(UUID id, UUID doctorId) {
		VideoSession fetched = prefetchSession(id, doctorId, false);
		return locked(fetched, () -> {
			Consultation c = assignedDoctorForUpdate(id, doctorId);
			if (c.status() != ConsultationStatus.ASSIGNED || c.joinedAt() == null) {
				throw new ApiException(HttpStatus.CONFLICT, "Invalid state",
						"Start requires ASSIGNED with the patient joined, not " + c.status());
			}
			attachSession(c, fetched);
			c.start(now());
			c.bump();
			Map<String, Object> payload = new java.util.LinkedHashMap<>();
			payload.put("consultationId", c.id());
			payload.put("patientId", c.patientId());
			payload.put("doctorId", c.doctorId());
			payload.put("sessionId", c.sessionId());
			payload.put("startedAt", c.startedAt());
			outbox.append(MessagingTopology.EVENTS_EXCHANGE, "consultation.started", "ConsultationStarted", c.id(), c.version(),
					doctorId, payload);
			evictAfterCommit(id);
			return c;
		});
	}

	@Transactional
	public Consultation complete(UUID id, UUID doctorId, String clinicalSummary, java.util.List<String> diagnosisCodes) {
		Consultation c = assignedDoctorForUpdate(id, doctorId);
		if (c.status() != ConsultationStatus.IN_PROGRESS) {
			throw new ApiException(HttpStatus.CONFLICT, "Invalid state", "Complete requires IN_PROGRESS, not " + c.status());
		}
		c.complete(now());
		c.bump();
		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("consultationId", c.id());
		payload.put("patientId", c.patientId());
		payload.put("doctorId", c.doctorId());
		payload.put("sessionId", c.sessionId());
		payload.put("clinicalSummary", clinicalSummary);
		payload.put("diagnosisCodes", diagnosisCodes);
		payload.put("startedAt", c.startedAt());
		payload.put("completedAt", c.completedAt());
		outbox.append(MessagingTopology.EVENTS_EXCHANGE, "consultation.completed", "ConsultationCompleted", c.id(),
				c.version(), doctorId, payload);
		evictAfterCommit(id);
		return c;
	}

	/** R-F4-05: re-checked under the row lock, so a join committed meanwhile wins. */
	@Transactional
	public boolean markNoShow(UUID id, Instant cutoff) {
		Consultation c = repo.findForUpdate(id).orElse(null);
		if (c == null || c.status() != ConsultationStatus.ASSIGNED || c.joinedAt() != null || c.assignedCommittedAt() == null
				|| !c.assignedCommittedAt().isBefore(cutoff)) {
			return false;
		}
		c.noShow();
		c.bump();
		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("consultationId", c.id());
		payload.put("patientId", c.patientId());
		payload.put("doctorId", c.doctorId());
		payload.put("assignedAt", c.assignedAt());
		payload.put("noShowAt", now());
		outbox.append(MessagingTopology.EVENTS_EXCHANGE, "consultation.no-show", "ConsultationNoShow", c.id(), c.version(),
				null, payload);
		evictAfterCommit(id);
		return true;
	}

	/** Spec §4: WAITING consultations for care-queue's Redis rebuild. */
	@Transactional(readOnly = true)
	public java.util.List<Map<String, Object>> waitingSnapshot() {
		return repo.findByStatusOrderByRequestedAt(ConsultationStatus.WAITING).stream().map(c -> {
			Map<String, Object> row = new java.util.LinkedHashMap<>();
			row.put("consultationId", c.id());
			row.put("patientId", c.patientId());
			row.put("priority", c.currentPriority());
			row.put("triageVersion", c.triageVersion());
			row.put("requestedAt", c.requestedAt());
			row.put("symptoms", json.readTree(c.symptoms()));
			return row;
		}).toList();
	}

	private Consultation assignedDoctorForUpdate(UUID id, UUID doctorId) {
		Consultation c = repo.findForUpdate(id).orElseThrow(ConsultationService::notFound);
		if (doctorId == null || !doctorId.equals(c.doctorId())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden", "Not the assigned doctor of this consultation");
		}
		return c;
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	// --- Event projections (forward-only, idempotent; see listeners for inbox + MDC) ---

	@Transactional
	public void onPatientTriaged(UUID id, Priority priority, int triageVersion) {
		project(id, c -> {
			boolean changed = c.applyTriage(priority, triageVersion);
			return c.applyForward(ConsultationStatus.TRIAGED) || changed;
		});
	}

	@Transactional
	public void onPriorityOverridden(UUID id, Priority newPriority, int triageVersion) {
		project(id, c -> c.applyTriage(newPriority, triageVersion));
	}

	@Transactional
	public void onPatientEnqueued(UUID id, Priority queue, int triageVersion) {
		project(id, c -> {
			boolean changed = c.applyForward(ConsultationStatus.WAITING);
			return c.applyQueuePriority(queue, triageVersion) || changed;
		});
	}

	private void project(UUID id, java.util.function.Predicate<Consultation> change) {
		Consultation c = repo.findForUpdate(id).orElse(null);
		if (c == null) {
			log.warn("Event for unknown consultation {}", id);
			return;
		}
		if (c.status().isTerminal()) {
			return;
		}
		if (change.test(c)) {
			c.bump();
			evictAfterCommit(id);
		}
	}

	private Consultation ownedForUpdate(UUID id, UUID patientId) {
		Consultation c = repo.findForUpdate(id).orElseThrow(ConsultationService::notFound);
		if (!c.patientId().equals(patientId)) {
			throw forbidden();
		}
		return c;
	}

	/** After commit, so a concurrent reader cannot re-cache the pre-change state. */
	private void evictAfterCommit(UUID id) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					caches.getCache(CACHE).evict(id.toString());
				}
				catch (RuntimeException ex) {
					// the entry still expires by TTL (30 s); never fail a committed write over the cache
					log.warn("Cache eviction failed for {}: {}", id, ex.toString());
				}
			}
		});
	}

	ConsultationView toView(Consultation c) {
		return new ConsultationView(c.id(), c.patientId(), c.status(), c.currentPriority(),
				json.readValue(c.symptoms(), SymptomReport.class), c.doctorId(),
				c.sessionId() == null ? null : new ConsultationView.VideoSession(c.sessionId(), c.patientJoinUrl()), c.requestedAt(), c.assignedAt(),
				c.startedAt(), c.completedAt(), c.version());
	}

	public static ApiException forbidden() {
		return new ApiException(HttpStatus.FORBIDDEN, "Forbidden", "Not the owner of this consultation");
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "Not found", "Consultation not found");
	}

	private static ApiException activeExists() {
		return new ApiException(HttpStatus.CONFLICT, "Active consultation exists", "The patient already has an active consultation");
	}
}
