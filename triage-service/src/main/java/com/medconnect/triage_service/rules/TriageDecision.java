package com.medconnect.triage_service.rules;

import com.medconnect.triage_service.domain.Priority;

public record TriageDecision(Priority priority, String ruleId) {
}
