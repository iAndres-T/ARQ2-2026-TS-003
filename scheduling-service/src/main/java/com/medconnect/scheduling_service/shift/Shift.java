package com.medconnect.scheduling_service.shift;

import java.time.Instant;
import java.util.UUID;

public record Shift(UUID shiftId, UUID doctorId, Instant startsAt, Instant endsAt) {
}
