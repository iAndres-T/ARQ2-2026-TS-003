package com.medconnect.consultation_service;

import static com.medconnect.consultation_service.domain.ConsultationStatus.ASSIGNED;
import static com.medconnect.consultation_service.domain.ConsultationStatus.CANCELLED;
import static com.medconnect.consultation_service.domain.ConsultationStatus.COMPLETED;
import static com.medconnect.consultation_service.domain.ConsultationStatus.IN_PROGRESS;
import static com.medconnect.consultation_service.domain.ConsultationStatus.NO_SHOW;
import static com.medconnect.consultation_service.domain.ConsultationStatus.REQUESTED;
import static com.medconnect.consultation_service.domain.ConsultationStatus.TRIAGED;
import static com.medconnect.consultation_service.domain.ConsultationStatus.WAITING;
import static com.medconnect.consultation_service.domain.ConsultationStatus.canTransition;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medconnect.consultation_service.domain.Consultation;
import com.medconnect.consultation_service.domain.ConsultationStatus;

class ConsultationStateMachineTest {

	// R-F4-06
	@Test
	void invalidTransitionRejected() {
		assertThat(canTransition(COMPLETED, WAITING)).isFalse();
		assertThat(canTransition(WAITING, REQUESTED)).isFalse();
		assertThat(canTransition(IN_PROGRESS, CANCELLED)).isFalse();
		assertThat(canTransition(WAITING, NO_SHOW)).isFalse();
		assertThat(canTransition(REQUESTED, WAITING)).isFalse();
	}

	@Test
	void allowedTransitions() {
		assertThat(canTransition(REQUESTED, TRIAGED)).isTrue();
		assertThat(canTransition(TRIAGED, WAITING)).isTrue();
		assertThat(canTransition(WAITING, ASSIGNED)).isTrue();
		assertThat(canTransition(ASSIGNED, IN_PROGRESS)).isTrue();
		assertThat(canTransition(IN_PROGRESS, COMPLETED)).isTrue();
		for (ConsultationStatus from : new ConsultationStatus[] { REQUESTED, TRIAGED, WAITING, ASSIGNED }) {
			assertThat(canTransition(from, CANCELLED)).isTrue();
		}
		assertThat(canTransition(ASSIGNED, NO_SHOW)).isTrue();
	}

	@Test
	void applyForwardIgnoresEarlierTargetsAndTerminalStates() {
		Consultation c = Consultation.request(UUID.randomUUID(), "{}", Instant.now());
		assertThat(c.applyForward(WAITING)).isTrue();
		assertThat(c.applyForward(TRIAGED)).isFalse();
		assertThat(c.status()).isEqualTo(WAITING);
		c.cancel();
		assertThat(c.applyForward(ASSIGNED)).isFalse();
		assertThat(c.status()).isEqualTo(CANCELLED);
	}
}
