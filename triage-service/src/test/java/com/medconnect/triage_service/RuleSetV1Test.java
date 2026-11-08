package com.medconnect.triage_service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.medconnect.triage_service.domain.MainSymptom;
import com.medconnect.triage_service.domain.Priority;
import com.medconnect.triage_service.domain.SymptomReport;
import com.medconnect.triage_service.domain.SymptomReport.DurationUnit;
import com.medconnect.triage_service.domain.SymptomReport.RedFlags;
import com.medconnect.triage_service.domain.SymptomReport.SymptomDuration;
import com.medconnect.triage_service.rules.RuleSetV1;
import com.medconnect.triage_service.rules.TriageDecision;

class RuleSetV1Test {

	static SymptomReport report(MainSymptom s, int pain, boolean breathing, boolean unconscious, String description) {
		return new SymptomReport(s, pain, new SymptomDuration(1, DurationUnit.HOURS), 40, new RedFlags(breathing, unconscious),
				description);
	}

	static void assertDecision(SymptomReport r, Priority p, String ruleId) {
		assertThat(RuleSetV1.evaluate(r)).isEqualTo(new TriageDecision(p, ruleId));
	}

	@Test // S-F3-01
	void chestPainIsHighR2() {
		assertDecision(report(MainSymptom.CHEST_PAIN, 9, false, false, null), Priority.HIGH, "R2_CRITICAL_SYMPTOM");
		assertDecision(report(MainSymptom.SHORTNESS_OF_BREATH, 1, false, false, null), Priority.HIGH, "R2_CRITICAL_SYMPTOM");
	}

	@Test // S-F3-02
	void coughWithBreathingRedFlagIsHighR1() {
		assertDecision(report(MainSymptom.COUGH, 2, true, false, null), Priority.HIGH, "R1_RED_FLAG");
	}

	@Test // S-F3-03
	void feverPain8IsMediumR3() {
		assertDecision(report(MainSymptom.FEVER, 8, false, false, null), Priority.MEDIUM, "R3_FEVER_HIGH_PAIN");
	}

	@Test
	void feverPain7IsMediumR3() {
		assertDecision(report(MainSymptom.FEVER, 7, false, false, null), Priority.MEDIUM, "R3_FEVER_HIGH_PAIN");
	}

	@Test
	void feverPain6IsLowR4() {
		assertDecision(report(MainSymptom.FEVER, 6, false, false, null), Priority.LOW, "R4_DEFAULT");
	}

	@Test // S-F3-04
	void coughPain2IsLowR4() {
		assertDecision(report(MainSymptom.COUGH, 2, false, false, null), Priority.LOW, "R4_DEFAULT");
	}

	@Test
	void redFlagBeatsCriticalSymptom() {
		assertDecision(report(MainSymptom.CHEST_PAIN, 9, false, true, null), Priority.HIGH, "R1_RED_FLAG");
	}

	@Test
	void descriptionIsIgnored() {
		assertDecision(report(MainSymptom.COUGH, 2, false, false, "chest pain!!"), Priority.LOW, "R4_DEFAULT");
		assertThat(RuleSetV1.VERSION).isEqualTo("v1");
	}
}
