package com.medconnect.triage_service.rules;

import com.medconnect.triage_service.domain.MainSymptom;
import com.medconnect.triage_service.domain.Priority;
import com.medconnect.triage_service.domain.SymptomReport;

/** Rule set v1 (spec R-F3-02): first match wins, the free-text description is never read. Pure function. */
public final class RuleSetV1 {

	public static final String VERSION = "v1";

	private RuleSetV1() {
	}

	public static TriageDecision evaluate(SymptomReport s) {
		if (Boolean.TRUE.equals(s.redFlags().difficultyBreathing()) || Boolean.TRUE.equals(s.redFlags().lossOfConsciousness())) {
			return new TriageDecision(Priority.HIGH, "R1_RED_FLAG");
		}
		if (s.mainSymptom() == MainSymptom.CHEST_PAIN || s.mainSymptom() == MainSymptom.SHORTNESS_OF_BREATH) {
			return new TriageDecision(Priority.HIGH, "R2_CRITICAL_SYMPTOM");
		}
		if (s.mainSymptom() == MainSymptom.FEVER && s.painLevel() >= 7) {
			return new TriageDecision(Priority.MEDIUM, "R3_FEVER_HIGH_PAIN");
		}
		return new TriageDecision(Priority.LOW, "R4_DEFAULT");
	}
}
