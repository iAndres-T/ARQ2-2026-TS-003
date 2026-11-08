package com.medconnect.scheduling_service.availability;

import java.time.Instant;
import java.util.UUID;

public record DoctorAvailability(UUID doctorId, String status, Instant since, UUID shiftId) {
}
