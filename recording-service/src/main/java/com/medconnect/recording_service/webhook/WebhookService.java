package com.medconnect.recording_service.webhook;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.medconnect.common.messaging.MessagingTopology;
import com.medconnect.common.messaging.OutboxWriter;
import com.medconnect.common.web.ApiException;
import com.medconnect.recording_service.domain.Recording;
import com.medconnect.recording_service.domain.RecordingRepository;
import com.medconnect.recording_service.jobs.RecordingTimeoutJob;
import com.medconnect.recording_service.storage.RecordingStorage;
import com.medconnect.recording_service.storage.RecordingStorage.StoredObject;

import tools.jackson.databind.JsonNode;

/**
 * Webhook processing. The provider recording id is claimed first (PROCESSING row, unique constraint), then the
 * object is promoted outside any transaction, then the outcome and its outbox event commit together.
 * Promote failures answer 503 so the provider retries; the third failure ends in FAILED + RecordingFailed (202).
 */
@Service
class WebhookService {

	private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

	private final RecordingRepository recordings;
	private final RecordingStorage storage;
	private final OutboxWriter outbox;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final int maxAttempts;

	WebhookService(RecordingRepository recordings, RecordingStorage storage, OutboxWriter outbox, JdbcTemplate jdbc,
			TransactionTemplate tx, @Value("${medconnect.recording.max-attempts}") int maxAttempts) {
		this.recordings = recordings;
		this.storage = storage;
		this.outbox = outbox;
		this.jdbc = jdbc;
		this.tx = tx;
		this.maxAttempts = maxAttempts;
	}

	void handle(JsonNode body) {
		String type = text(body, "type");
		String sessionId = text(body, "sessionId");
		String providerRecordingId = text(body, "providerRecordingId");
		if (type == null || sessionId == null || providerRecordingId == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Malformed payload", "type, sessionId and providerRecordingId are required");
		}
		if (!type.equals("recording.completed") && !type.equals("recording.failed")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Malformed payload", "Unsupported type");
		}
		UUID consultationId = jdbc.query("select consultation_id from session_map where session_id = ?",
				(rs, i) -> rs.getObject(1, UUID.class), sessionId).stream().findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Session not mapped yet", "Retry later"));

		if (type.equals("recording.failed")) {
			String reason = text(body, "reason") != null ? text(body, "reason") : "Provider reported a recording failure";
			tx.executeWithoutResult(s -> {
				Recording r = claim(consultationId, providerRecordingId);
				fail(r, reason, 1);
			});
			return;
		}

		JsonNode duration = body.get("durationSeconds");
		String incomingKey = text(body, "objectKey") != null ? text(body, "objectKey") : "incoming/" + providerRecordingId + ".mp4";
		Recording claimed = tx.execute(s -> {
			Recording reopened = reopenTimedOut(consultationId, providerRecordingId);
			return reopened != null ? reopened : claim(consultationId, providerRecordingId);
		});
		StoredObject stored;
		try {
			stored = storage.promote(incomingKey, consultationId, claimed.id);
		}
		catch (RuntimeException ex) {
			log.warn("Promote failed for recording {}: {}", claimed.id, ex.toString());
			boolean exhausted = Boolean.TRUE.equals(tx.execute(s -> {
				Recording r = recordings.findForUpdate(claimed.id).orElseThrow();
				r.attempts++;
				if (r.attempts < maxAttempts) {
					return false;
				}
				fail(r, "Could not store the recording: " + ex.getClass().getSimpleName(), r.attempts);
				return true;
			}));
			if (exhausted) {
				return;
			}
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Recording not stored yet", "Retry later");
		}
		tx.executeWithoutResult(s -> {
			Recording r = recordings.findForUpdate(claimed.id).orElseThrow();
			if (!r.status.equals("PROCESSING")) {
				return; // a concurrent delivery finished first
			}
			r.status = "AVAILABLE";
			r.url = stored.uri();
			r.sizeBytes = stored.sizeBytes();
			r.checksumSha256 = stored.sha256Hex();
			r.durationSeconds = (duration == null || duration.isNull()) ? null : duration.asInt();
			r.recordedAt = Instant.now();
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("recordingId", r.id);
			payload.put("consultationId", r.consultationId);
			payload.put("url", r.url);
			payload.put("durationSeconds", r.durationSeconds);
			payload.put("recordedAt", r.recordedAt);
			payload.put("sizeBytes", r.sizeBytes);
			payload.put("checksumSha256", r.checksumSha256);
			outbox.append(MessagingTopology.EVENTS_EXCHANGE, "recording.available", "RecordingAvailable", r.consultationId, 1, null, payload);
		});
	}

	/**
	 * A completed callback arriving after the timeout job failed the consultation's recording re-opens that same row
	 * (one row per consultation), adopting the callback's provider recording id. Other FAILED/AVAILABLE rows: null.
	 */
	private Recording reopenTimedOut(UUID consultationId, String providerRecordingId) {
		Recording latest = recordings.findFirstByConsultationIdOrderByCreatedAtDesc(consultationId).orElse(null);
		if (latest == null) {
			return null;
		}
		Recording r = recordings.findForUpdate(latest.id).orElseThrow();
		if (!r.status.equals("FAILED") || !RecordingTimeoutJob.REASON.equals(r.failureReason)) {
			return null;
		}
		if (!providerRecordingId.equals(r.providerRecordingId)
				&& recordings.findByProviderRecordingId(providerRecordingId).isPresent()) {
			return null; // belongs to another row: normal claim decides (409)
		}
		r.providerRecordingId = providerRecordingId;
		r.status = "PROCESSING";
		r.failureReason = null;
		r.attempts = 0;
		return recordings.saveAndFlush(r);
	}

	/** Returns the PROCESSING row for this provider recording; 409 when it is already settled or concurrently claimed. */
	private Recording claim(UUID consultationId, String providerRecordingId) {
		Recording existing = recordings.findByProviderRecordingId(providerRecordingId).orElse(null);
		if (existing != null) {
			if (!existing.status.equals("PROCESSING")) {
				throw duplicate();
			}
			return existing;
		}
		try {
			return recordings.saveAndFlush(new Recording(UUID.randomUUID(), consultationId, providerRecordingId, "PROCESSING"));
		}
		catch (DataIntegrityViolationException ex) {
			throw duplicate();
		}
	}

	private void fail(Recording r, String reason, int attempts) {
		r.status = "FAILED";
		r.attempts = attempts;
		r.failureReason = reason;
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("recordingId", r.id);
		payload.put("consultationId", r.consultationId);
		payload.put("reason", reason);
		payload.put("attempts", attempts);
		payload.put("failedAt", Instant.now());
		outbox.append(MessagingTopology.EVENTS_EXCHANGE, "recording.failed", "RecordingFailed", r.consultationId, 1, null, payload);
	}

	private static ApiException duplicate() {
		return new ApiException(HttpStatus.CONFLICT, "Duplicate callback", "This provider recording was already processed");
	}

	private static String text(JsonNode body, String field) {
		JsonNode n = body.get(field);
		return (n == null || !n.isString() || n.asString().isBlank()) ? null : n.asString();
	}
}
