package com.medconnect.care_queue_service.app;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.medconnect.care_queue_service.outbox.OutboxRecords;
import com.medconnect.care_queue_service.redis.QueueKeys;
import com.medconnect.common.messaging.EventEnvelope;

import tools.jackson.databind.JsonNode;

/**
 * Applies triage, cancellation and doctor events to the Redis queues. Each change, its inbox dedupe and its
 * outbox event happen in one Lua script, so a crash can neither lose nor half-apply an event.
 */
@Service
public class QueueService {

	private static final Logger log = LoggerFactory.getLogger(QueueService.class);
	private static final List<String> UNAVAILABLE = List.of("BUSY", "OFFLINE");

	private final StringRedisTemplate redis;
	private final RedisScript<String> enqueueOrMoveScript;
	private final RedisScript<String> removeScript;
	private final RedisScript<String> doctorStatusScript;
	private final OutboxRecords outbox;

	QueueService(StringRedisTemplate redis, RedisScript<String> enqueueOrMoveScript, RedisScript<String> removeScript,
			RedisScript<String> doctorStatusScript, OutboxRecords outbox) {
		this.redis = redis;
		this.enqueueOrMoveScript = enqueueOrMoveScript;
		this.removeScript = removeScript;
		this.doctorStatusScript = doctorStatusScript;
		this.outbox = outbox;
	}

	/** PatientTriaged: initial insert, or RECLASSIFIED move after a re-triage. */
	public String onPatientTriaged(EventEnvelope e, String consumer) {
		JsonNode p = e.payload();
		JsonNode symptoms = p.get("symptoms");
		return enqueueOrMove(new Move(id(p, "consultationId"), text(p, "priority"), p.required("triageVersion").asInt(),
				Instant.parse(text(p, "requestedAt")), "RECLASSIFIED", uuid(p, "patientId"),
				(symptoms == null || symptoms.isNull()) ? null : symptoms.toString()), e, null, consumer);
	}

	/** PriorityOverridden: OVERRIDE move keeping the original score (S-F3-07). */
	public String onPriorityOverridden(EventEnvelope e, String consumer) {
		JsonNode p = e.payload();
		return enqueueOrMove(new Move(id(p, "consultationId"), text(p, "newPriority"), p.required("triageVersion").asInt(),
				Instant.parse(text(p, "requestedAt")), "OVERRIDE", uuid(p, "patientId"), null), e, uuid(p, "overriddenBy"),
				consumer);
	}

	/**
	 * Runs {@code enqueue_or_move.lua}. {@code cause} and {@code consumer} may be null for internal moves (aging),
	 * which then skip the inbox dedupe.
	 */
	public String enqueueOrMove(Move move, EventEnvelope cause, UUID actorId, String consumer) {
		QueueKeys.queue(move.priority()); // validates before anything is written
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("consultationId", move.consultationId());
		payload.put("queue", move.priority());
		payload.put("previousQueue", null); // set by the script
		payload.put("reason", move.reason()); // INITIAL on first insert, set by the script
		payload.put("triageVersion", move.triageVersion());
		payload.put("requestedAt", move.requestedAt());
		payload.put("enqueuedAt", Instant.now());
		String record = outbox.build("queue.patient-enqueued", "PatientEnqueued", move.consultationId(),
				move.triageVersion(), (cause == null) ? null : cause.correlationId(),
				(cause == null) ? null : cause.eventId(), actorId, payload);

		return execute(move, record, inboxKey(consumer, cause));
	}

	/**
	 * Redis rebuild replay (spec §4): first insert only (reason INITIAL), no inbox and no PatientEnqueued, because
	 * consultation-service already holds these consultations as WAITING. Tombstones and newer entries still win.
	 */
	public String restore(Move move) {
		QueueKeys.queue(move.priority());
		return execute(move, "", "");
	}

	private String execute(Move move, String record, String inboxKey) {
		List<String> keys = new ArrayList<>(QueueKeys.consultationKeys(move.consultationId()));
		keys.add(QueueKeys.OUTBOX_PENDING);
		keys.add(inboxKey);
		String result = redis.execute(enqueueOrMoveScript, keys, move.consultationId().toString(), move.priority(),
				String.valueOf(move.triageVersion()), String.valueOf(move.requestedAt().toEpochMilli()), move.reason(),
				record, (move.patientId() == null) ? "" : move.patientId().toString(),
				(move.symptomsJson() == null) ? "" : move.symptomsJson(), seconds(QueueKeys.INBOX_TTL));
		log.info("enqueue_or_move consultation={} priority={} triageVersion={} -> {}", move.consultationId(),
				move.priority(), move.triageVersion(), result);
		return result;
	}

	/** ConsultationCancelled: remove from any queue and tombstone (S-F4-10). */
	public String onConsultationCancelled(EventEnvelope e, String consumer) {
		UUID cid = id(e.payload(), "consultationId");
		List<String> keys = new ArrayList<>(QueueKeys.consultationKeys(cid));
		keys.add(inboxKey(consumer, e));
		keys.add(QueueKeys.DOCTORS_FREE);
		String result = redis.execute(removeScript, keys, cid.toString(), seconds(QueueKeys.TOMBSTONE_TTL),
				seconds(QueueKeys.INBOX_TTL));
		log.info("remove consultation={} -> {}", cid, result);
		return result;
	}

	public String onDoctorBecameAvailable(EventEnvelope e, String consumer) {
		return doctorStatus(e, "FREE", consumer);
	}

	public String onDoctorBecameUnavailable(EventEnvelope e, String consumer) {
		String status = text(e.payload(), "status");
		if (!UNAVAILABLE.contains(status)) {
			throw new IllegalArgumentException("Unknown doctor status " + status);
		}
		return doctorStatus(e, status, consumer);
	}

	/** Ordering is per doctor: the envelope aggregateVersion is scheduling-service's per-doctor version. */
	private String doctorStatus(EventEnvelope e, String status, String consumer) {
		UUID did = id(e.payload(), "doctorId");
		String result = redis.execute(doctorStatusScript,
				List.of(QueueKeys.doctor(did), QueueKeys.DOCTORS_FREE, inboxKey(consumer, e)), did.toString(), status,
				String.valueOf(e.aggregateVersion()), seconds(QueueKeys.INBOX_TTL));
		log.info("doctor={} status={} version={} -> {}", did, status, e.aggregateVersion(), result);
		return result;
	}

	private static String inboxKey(String consumer, EventEnvelope cause) {
		return (consumer == null || cause == null) ? "" : QueueKeys.inbox(consumer, cause.eventId());
	}

	private static String seconds(Duration d) {
		return String.valueOf(d.toSeconds());
	}

	private static String text(JsonNode payload, String field) {
		return payload.required(field).asString();
	}

	private static UUID id(JsonNode payload, String field) {
		return UUID.fromString(text(payload, field));
	}

	private static UUID uuid(JsonNode payload, String field) {
		JsonNode node = payload.get(field);
		return (node == null || node.isNull()) ? null : UUID.fromString(node.asString());
	}

	/** One enqueue/move request; {@code patientId} and {@code symptomsJson} may be null (kept as stored). */
	public record Move(UUID consultationId, String priority, int triageVersion, Instant requestedAt, String reason,
			UUID patientId, String symptomsJson) {
	}
}
