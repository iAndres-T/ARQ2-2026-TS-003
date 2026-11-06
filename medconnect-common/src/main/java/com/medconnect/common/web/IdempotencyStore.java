package com.medconnect.common.web;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Persistence for {@link IdempotencyService}; JDBC by default, Redis in care-queue-service. */
public interface IdempotencyStore {

	Duration TTL = Duration.ofHours(24);

	/** Reserves the key; empty when reserved now, otherwise the stored response (status null = still running). */
	Optional<StoredResponse> reserve(String key, UUID principal);

	void complete(String key, UUID principal, int status, String bodyJson);

	void release(String key, UUID principal);

	record StoredResponse(Integer status, String bodyJson) {
	}
}
