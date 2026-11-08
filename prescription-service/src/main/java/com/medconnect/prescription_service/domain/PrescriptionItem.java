package com.medconnect.prescription_service.domain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Spec §1 PrescriptionItem; the same shape is the request item, the stored item and the event item. */
public record PrescriptionItem(
		@NotBlank @Size(max = 200) String medicationName,
		@NotBlank @Size(max = 100) String dosage,
		@NotBlank @Size(max = 100) String frequency,
		@NotNull @Min(1) @Max(365) Integer durationDays,
		@Size(max = 500) String instructions) {
}
