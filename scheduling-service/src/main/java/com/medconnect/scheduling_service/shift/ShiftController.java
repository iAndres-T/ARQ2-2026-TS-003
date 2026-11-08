package com.medconnect.scheduling_service.shift;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.scheduling_service.availability.AvailabilityService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/shifts")
class ShiftController {

	private final AvailabilityService service;

	ShiftController(AvailabilityService service) {
		this.service = service;
	}

	record CreateShift(@NotNull UUID doctorId, @NotNull Instant startsAt, @NotNull Instant endsAt) {
	}

	record Page(List<Shift> items, String nextCursor) {
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Shift create(@Valid @RequestBody CreateShift body) {
		return service.createShift(body.doctorId(), body.startsAt(), body.endsAt());
	}

	@GetMapping
	Page list(@RequestParam Instant from, @RequestParam Instant to, @RequestParam(required = false) UUID doctorId) {
		return new Page(service.shifts(from, to, doctorId), null);
	}
}
