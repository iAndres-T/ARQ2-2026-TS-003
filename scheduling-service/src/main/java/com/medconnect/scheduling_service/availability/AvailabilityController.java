package com.medconnect.scheduling_service.availability;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.common.security.CurrentUser;
import com.medconnect.common.web.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/doctors")
class AvailabilityController {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAX_LIMIT = 200;

	private final AvailabilityService service;

	AvailabilityController(AvailabilityService service) {
		this.service = service;
	}

	record StatusRequest(@NotNull String status) {
	}

	record Page(List<DoctorAvailability> items, String nextCursor) {
	}

	@PutMapping("/me/availability")
	DoctorAvailability set(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StatusRequest body) {
		CurrentUser user = CurrentUser.from(jwt);
		if (user.doctorId() == null) {
			throw new ApiException(HttpStatus.FORBIDDEN, "Not a doctor", "The token carries no doctorId");
		}
		return service.setStatus(user.doctorId(), body.status(), user.sub());
	}

	@GetMapping("/availability")
	Page list(@RequestParam(required = false) String status, @RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String cursor) {
		int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
		List<DoctorAvailability> rows = service.list(status, decode(cursor), size + 1);
		if (rows.size() <= size) {
			return new Page(rows, null);
		}
		List<DoctorAvailability> items = rows.subList(0, size);
		return new Page(items, Base64.getUrlEncoder().withoutPadding()
			.encodeToString(items.get(size - 1).doctorId().toString().getBytes(StandardCharsets.UTF_8)));
	}

	private static UUID decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid cursor", "The cursor is not valid");
		}
	}
}
