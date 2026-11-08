package com.medconnect.care_queue_service.api;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.care_queue_service.outbox.OutboxRecords;
import com.medconnect.care_queue_service.redis.QueueKeys;
import com.medconnect.care_queue_service.redis.RedisIdempotencyStore;
import com.medconnect.common.messaging.OutboxWriter;
import com.medconnect.common.security.CurrentUser;
import com.medconnect.common.web.ApiException;
import com.medconnect.common.web.IdempotencyService;
import com.medconnect.common.web.IdempotencyStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/queue/claims}: claim.lua pops the next patient under a lease, then confirm.lua drops the
 * lease and pushes PatientAssigned to the outbox in one step. A patient cancelled during the lease is skipped and
 * the next one is tried in the same request.
 */
@RestController
public class ClaimController {

	private static final Logger log = LoggerFactory.getLogger(ClaimController.class);
	private static final int MAX_WAIT_SECONDS = 30;
	private static final long POLL_MS = 200;

	private final StringRedisTemplate redis;
	private final RedisScript<String> claimScript;
	private final RedisScript<String> confirmScript;
	private final OutboxRecords outbox;
	private final JsonMapper json;
	private final IdempotencyService idempotency;
	private final RedisIdempotencyStore idempotencyStore;
	private final Duration lease;

	ClaimController(StringRedisTemplate redis, RedisScript<String> claimScript, RedisScript<String> confirmScript,
			OutboxRecords outbox, JsonMapper json, RedisIdempotencyStore idempotencyStore, @Value("${medconnect.queue.lease:30s}") Duration lease) {
		this.redis = redis;
		this.claimScript = claimScript;
		this.confirmScript = confirmScript;
		this.outbox = outbox;
		this.json = json;
		this.lease = lease;
		this.idempotencyStore = idempotencyStore;
		this.idempotency = new IdempotencyService(idempotencyStore, json, null);
	}

	@PostMapping("/api/v1/queue/claims")
	public ResponseEntity<?> claim(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
			@RequestParam(defaultValue = "0") int waitSeconds) {
		CurrentUser user = CurrentUser.from(jwt);
		if (user.doctorId() == null) {
			throw new ApiException(HttpStatus.FORBIDDEN, "Not a doctor", "Token has no doctorId claim");
		}
		if (waitSeconds < 0 || waitSeconds > MAX_WAIT_SECONDS) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid waitSeconds", "waitSeconds must be between 0 and 30");
		}
		UUID did = user.doctorId();
		return idempotency.execute(key, did, () -> {
			long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
			while (true) {
				Optional<Assignment> assignment = claimNext(did, user.sub(), key);
				if (assignment.isPresent()) {
					return ResponseEntity.status(HttpStatus.CREATED).body(assignment.get());
				}
				if (System.currentTimeMillis() + POLL_MS > deadline) {
					return ResponseEntity.noContent().build();
				}
				sleep();
			}
		});
	}

	private Optional<Assignment> claimNext(UUID did, UUID actorId, String idempotencyKey) {
		while (true) {
			String cid = redis.execute(claimScript,
					List.of(QueueKeys.doctor(did), QueueKeys.DOCTORS_FREE, QueueKeys.queue("HIGH"), QueueKeys.queue("MEDIUM"),
							QueueKeys.queue("LOW"), QueueKeys.CLAIMED),
					did.toString(), String.valueOf(System.currentTimeMillis()), String.valueOf(lease.toMillis()));
			if ("NOT_FREE".equals(cid)) {
				throw new ApiException(HttpStatus.CONFLICT, "Doctor not FREE",
						"Only a FREE doctor with an active shift can claim a patient");
			}
			if (cid == null) {
				return Optional.empty();
			}
			UUID consultationId = UUID.fromString(cid);
			Map<Object, Object> entry = redis.opsForHash().entries(QueueKeys.entry(consultationId));
			Assignment a = new Assignment(consultationId, uuid(entry.get("patientId")), did, (String) entry.get("queue"),
					symptoms(entry.get("symptoms")), Instant.now());
			String result = redis.execute(confirmScript,
					List.of(QueueKeys.entry(consultationId), QueueKeys.tombstone(consultationId), QueueKeys.CLAIMED,
							QueueKeys.OUTBOX_PENDING, QueueKeys.doctor(did), QueueKeys.DOCTORS_FREE,
							RedisIdempotencyStore.key(idempotencyKey, did)),
					cid, did.toString(), assignedRecord(a, entry.get("triageVersion"), actorId),
					String.valueOf(QueueKeys.TOMBSTONE_TTL.toSeconds()),
					// stored with the assignment so a retry replays it even if the reply or complete() is lost
					idempotencyStore.storedValue(HttpStatus.CREATED.value(), json.writeValueAsString(a)),
					String.valueOf(IdempotencyStore.TTL.toSeconds()));
			log.info("claim consultation={} doctor={} -> {}", cid, did, result);
			if ("CONFIRMED".equals(result)) {
				return Optional.of(a);
			}
			// CANCELLED or EXPIRED: the doctor is FREE again, try the next patient
		}
	}

	private String assignedRecord(Assignment a, Object triageVersion, UUID actorId) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("consultationId", a.consultationId());
		payload.put("patientId", a.patientId());
		payload.put("doctorId", a.doctorId());
		payload.put("priority", a.priority());
		payload.put("assignedAt", a.assignedAt());
		return outbox.build("queue.patient-assigned", "PatientAssigned", a.consultationId(),
				(triageVersion == null) ? 0 : Long.parseLong((String) triageVersion), correlationId(), null, actorId, payload);
	}

	private JsonNode symptoms(Object raw) {
		return (raw == null) ? null : json.readTree((String) raw);
	}

	private static UUID uuid(Object raw) {
		return (raw == null) ? null : UUID.fromString((String) raw);
	}

	private static UUID correlationId() {
		String id = MDC.get(OutboxWriter.CORRELATION_ID_MDC);
		return (id == null) ? null : UUID.fromString(id);
	}

	private static void sleep() {
		try {
			Thread.sleep(POLL_MS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while long-polling", ex);
		}
	}

	/** Redis down or timing out: spec 503 "Queue store unavailable". */
	@ExceptionHandler({ DataAccessResourceFailureException.class, QueryTimeoutException.class })
	ResponseEntity<ProblemDetail> storeUnavailable(RuntimeException ex) {
		log.warn("Queue store unavailable: {}", ex.getMessage());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Queue store unavailable");
		problem.setTitle("Service unavailable");
		problem.setType(java.net.URI.create("about:blank"));
		String correlationId = MDC.get(OutboxWriter.CORRELATION_ID_MDC);
		if (correlationId != null) {
			problem.setProperty("correlationId", correlationId);
		}
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
	}

	/** Spec §1 {@code Assignment}. */
	public record Assignment(UUID consultationId, UUID patientId, UUID doctorId, String priority, JsonNode symptoms,
			Instant assignedAt) {
	}
}
