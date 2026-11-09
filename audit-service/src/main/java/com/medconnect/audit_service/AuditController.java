package com.medconnect.audit_service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.common.web.ApiException;

import tools.jackson.databind.JsonNode;

/** Read-only by construction: the only mapping is GET. */
@RestController
@RequestMapping("/api/v1/audit")
class AuditController {

	static final int DEFAULT_LIMIT = 50;
	static final int MAX_LIMIT = 200;

	record Item(UUID eventId, String eventType, UUID aggregateId, UUID actorId, Instant occurredAt, JsonNode payload) {
	}

	record Page(List<Item> items, String nextCursor) {
	}

	private final AuditRepository repository;

	AuditController(AuditRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/events")
	Page events(@RequestParam(required = false) String aggregateId, @RequestParam(required = false) String eventType,
			@RequestParam(required = false) String actorId, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor) {
		Instant fromAt = instant("from", from);
		Instant toAt = instant("to", to);
		if (fromAt != null && toAt != null && fromAt.isAfter(toAt)) {
			throw bad("'from' must not be after 'to'");
		}
		int max = limit == null ? DEFAULT_LIMIT : integer(limit);
		Instant afterAt = null;
		UUID afterId = null;
		if (cursor != null) {
			try {
				String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
				afterAt = Instant.parse(parts[0]);
				afterId = UUID.fromString(parts[1]);
			}
			catch (RuntimeException ex) {
				throw bad("Invalid cursor");
			}
		}
		var filter = new AuditRepository.Filter(uuid("aggregateId", aggregateId), eventType, uuid("actorId", actorId), fromAt,
				toAt, afterAt, afterId);
		List<AuditRepository.Row> rows = repository.find(filter, max + 1);
		boolean more = rows.size() > max;
		List<AuditRepository.Row> page = more ? rows.subList(0, max) : rows;
		String next = null;
		if (more) {
			AuditRepository.Row last = page.get(page.size() - 1);
			next = Base64.getUrlEncoder().withoutPadding()
				.encodeToString((last.occurredAt() + "|" + last.eventId()).getBytes(StandardCharsets.UTF_8));
		}
		return new Page(page.stream().map(r -> new Item(r.eventId(), r.eventType(), r.aggregateId(), r.actorId(),
				r.occurredAt(), r.payload())).toList(), next);
	}

	private static int integer(String limit) {
		try {
			int n = Integer.parseInt(limit);
			if (n >= 1 && n <= MAX_LIMIT) {
				return n;
			}
		}
		catch (NumberFormatException ex) {
			// falls through to 400
		}
		throw bad("'limit' must be an integer between 1 and " + MAX_LIMIT);
	}

	private static Instant instant(String name, String value) {
		try {
			return value == null ? null : Instant.parse(value);
		}
		catch (DateTimeParseException ex) {
			throw bad("'" + name + "' must be an ISO-8601 instant");
		}
	}

	private static UUID uuid(String name, String value) {
		try {
			return value == null ? null : UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			throw bad("'" + name + "' must be a UUID");
		}
	}

	private static ApiException bad(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, "Invalid filters", detail);
	}
}
