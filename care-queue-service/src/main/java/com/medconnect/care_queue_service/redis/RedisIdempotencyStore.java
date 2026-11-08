package com.medconnect.care_queue_service.redis;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.medconnect.common.web.IdempotencyStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code idem:{principal}:{key}}: an empty value while the request runs (expires after {@link #RUNNING_TTL} so a
 * crashed request does not block the key for 24 h), then {@code {status, body}} for {@link IdempotencyStore#TTL}.
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

	static final Duration RUNNING_TTL = Duration.ofSeconds(60);
	private static final RedisScript<Long> RELEASE = RedisScript.of("""
			if redis.call('GET', KEYS[1]) == '' then return redis.call('DEL', KEYS[1]) end
			return 0""", Long.class);

	private final StringRedisTemplate redis;
	private final JsonMapper json;

	public RedisIdempotencyStore(StringRedisTemplate redis, JsonMapper json) {
		this.redis = redis;
		this.json = json;
	}

	public static String key(String key, UUID principal) {
		return "idem:" + principal + ":" + key;
	}

	@Override
	public Optional<StoredResponse> reserve(String key, UUID principal) {
		if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(key, principal), "", RUNNING_TTL))) {
			return Optional.empty();
		}
		String stored = redis.opsForValue().get(key(key, principal));
		if (stored == null || stored.isEmpty()) {
			return Optional.of(new StoredResponse(null, null)); // still running (or just expired): client retries
		}
		JsonNode node = json.readTree(stored);
		JsonNode body = node.get("body");
		return Optional.of(new StoredResponse(node.get("status").asInt(), (body == null || body.isNull()) ? null : body.asString()));
	}

	@Override
	public void complete(String key, UUID principal, int status, String bodyJson) {
		redis.opsForValue().set(key(key, principal), storedValue(status, bodyJson), TTL);
	}

	/** The completed-response value; confirm.lua writes it atomically with the assignment it describes. */
	public String storedValue(int status, String bodyJson) {
		Map<String, Object> value = new HashMap<>();
		value.put("status", status);
		value.put("body", bodyJson);
		return json.writeValueAsString(value);
	}

	@Override
	public void release(String key, UUID principal) {
		redis.execute(RELEASE, List.of(key(key, principal)));
	}
}
