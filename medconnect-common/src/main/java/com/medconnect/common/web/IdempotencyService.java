package com.medconnect.common.web;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Idempotency-Key} handling (spec §2): the first response for a key is replayed for 24 h. With a
 * {@link TransactionTemplate} (JDBC store) reservation, action and stored response commit or roll back together,
 * and a concurrent request with the same key waits on the key's row and then replays.
 */
public class IdempotencyService {

	public static final String HEADER = "Idempotency-Key";

	private final IdempotencyStore store;
	private final JsonMapper json;
	private final TransactionTemplate tx;

	/** @param tx transaction spanning reserve/action/complete, or {@code null} for non-transactional stores */
	public IdempotencyService(IdempotencyStore store, JsonMapper json, TransactionTemplate tx) {
		this.store = store;
		this.json = json;
		this.tx = tx;
	}

	public ResponseEntity<?> execute(String key, UUID principal, Supplier<ResponseEntity<?>> action) {
		if (key == null || key.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key", "Header Idempotency-Key is required");
		}
		return (tx == null) ? run(key, principal, action) : tx.execute(status -> run(key, principal, action));
	}

	private ResponseEntity<?> run(String key, UUID principal, Supplier<ResponseEntity<?>> action) {
		Optional<IdempotencyStore.StoredResponse> stored = store.reserve(key, principal);
		if (stored.isPresent()) {
			return replay(stored.get());
		}
		ResponseEntity<?> response;
		try {
			response = action.get();
		}
		catch (RuntimeException | Error ex) {
			store.release(key, principal);
			throw ex;
		}
		store.complete(key, principal, response.getStatusCode().value(),
				response.hasBody() ? json.writeValueAsString(response.getBody()) : null);
		return response;
	}

	private ResponseEntity<?> replay(IdempotencyStore.StoredResponse stored) {
		if (stored.status() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "Request in progress",
					"A request with this Idempotency-Key is still being processed");
		}
		return ResponseEntity.status(stored.status())
			.body(stored.bodyJson() == null ? null : json.readTree(stored.bodyJson()));
	}
}
