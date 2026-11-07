package com.medconnect.consultation_service.api.dto;

import jakarta.validation.constraints.Size;

public record CancelRequest(@Size(max = 500) String reason) {
}
