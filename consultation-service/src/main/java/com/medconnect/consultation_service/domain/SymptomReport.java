package com.medconnect.consultation_service.domain;

import java.io.Serializable;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Spec §1 SymptomReport, bounds verbatim. */
public record SymptomReport(
		@NotNull MainSymptom mainSymptom,
		@NotNull @Min(1) @Max(10) Integer painLevel,
		@NotNull @Valid SymptomDuration symptomDuration,
		@NotNull @Min(0) @Max(120) Integer age,
		@NotNull @Valid RedFlags redFlags,
		@Size(max = 1000) String description) implements Serializable {

	public enum DurationUnit {
		MINUTES, HOURS, DAYS
	}

	public record SymptomDuration(@NotNull @Min(1) @Max(999) Integer value, @NotNull DurationUnit unit) implements Serializable {
	}

	public record RedFlags(@NotNull Boolean difficultyBreathing, @NotNull Boolean lossOfConsciousness) implements Serializable {
	}
}
