package com.medconnect.consultation_service.domain;

import java.util.EnumSet;
import java.util.Set;

/** Spec §1 ConsultationStatus; declaration order is the forward path (R-F4-06). */
public enum ConsultationStatus {
	REQUESTED, TRIAGED, WAITING, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW;

	public static final Set<ConsultationStatus> ACTIVE = EnumSet.of(REQUESTED, TRIAGED, WAITING, ASSIGNED, IN_PROGRESS);

	public boolean isTerminal() {
		return this == COMPLETED || this == CANCELLED || this == NO_SHOW;
	}

	public static boolean canTransition(ConsultationStatus from, ConsultationStatus to) {
		return switch (from) {
			case REQUESTED -> to == TRIAGED || to == CANCELLED;
			case TRIAGED -> to == WAITING || to == CANCELLED;
			case WAITING -> to == ASSIGNED || to == CANCELLED;
			case ASSIGNED -> to == IN_PROGRESS || to == CANCELLED || to == NO_SHOW;
			case IN_PROGRESS -> to == COMPLETED;
			case COMPLETED, CANCELLED, NO_SHOW -> false;
		};
	}
}
