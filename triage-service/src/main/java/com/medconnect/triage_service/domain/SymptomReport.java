package com.medconnect.triage_service.domain;

/** Spec section 1 SymptomReport as received in events (already validated by consultation-service). */
public record SymptomReport(MainSymptom mainSymptom, Integer painLevel, SymptomDuration symptomDuration, Integer age,
		RedFlags redFlags, String description) {

	public enum DurationUnit {
		MINUTES, HOURS, DAYS
	}

	public record SymptomDuration(Integer value, DurationUnit unit) {
	}

	public record RedFlags(Boolean difficultyBreathing, Boolean lossOfConsciousness) {
	}
}
