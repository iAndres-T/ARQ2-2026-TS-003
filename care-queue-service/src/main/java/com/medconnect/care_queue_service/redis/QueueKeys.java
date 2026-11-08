package com.medconnect.care_queue_service.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Every Redis key care-queue-service uses (ADR-001, plan D5). */
public final class QueueKeys {

	public static final List<String> PRIORITIES = List.of("HIGH", "MEDIUM", "LOW");
	/** Entries under a claim lease (Task 10); scripts in this service treat it as "in no waiting queue". */
	public static final String CLAIMED = "queue:claimed";
	public static final String DOCTORS_FREE = "doctors:free";
	public static final String OUTBOX_PENDING = "outbox:pending";
	public static final String OUTBOX_INFLIGHT = "outbox:inflight";
	/** Outbox records that cannot be parsed; kept for inspection, never dropped. */
	public static final String OUTBOX_POISON = "outbox:poison";
	public static final String RELAY_LOCK = "outbox:relay-lock";

	public static final Duration TOMBSTONE_TTL = Duration.ofHours(24);
	// sizing: must outlast the 73-min retry chain (5s+30s+2m+10m+1h) with margin; later replays are caught by version checks
	public static final Duration INBOX_TTL = Duration.ofHours(6);

	private QueueKeys() {
	}

	/** {@code queue:HIGH|MEDIUM|LOW}: zset, member consultationId, score requestedAt ms. */
	public static String queue(String priority) {
		if (!PRIORITIES.contains(priority)) {
			throw new IllegalArgumentException("Unknown priority " + priority);
		}
		return "queue:" + priority;
	}

	/** Hash: queue, triageVersion, patientId, requestedAt, symptoms. */
	public static String entry(UUID consultationId) {
		return "entry:" + consultationId;
	}

	public static String tombstone(UUID consultationId) {
		return "tombstone:" + consultationId;
	}

	/** Hash: status (FREE|BUSY|OFFLINE), version. */
	public static String doctor(UUID doctorId) {
		return "doctor:" + doctorId;
	}

	public static String inbox(String consumer, UUID eventId) {
		return "inbox:" + consumer + ":" + eventId;
	}

	/** KEYS 1..6 shared by enqueue_or_move.lua and remove.lua. */
	public static List<String> consultationKeys(UUID consultationId) {
		return List.of(entry(consultationId), tombstone(consultationId), queue("HIGH"), queue("MEDIUM"), queue("LOW"), CLAIMED);
	}
}
