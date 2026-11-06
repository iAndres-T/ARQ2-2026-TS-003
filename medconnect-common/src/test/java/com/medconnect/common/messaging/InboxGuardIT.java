package com.medconnect.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.medconnect.common.TestContainersConfig;

@SpringBootTest
@Import(TestContainersConfig.class)
class InboxGuardIT {

	@Autowired InboxGuard inbox;

	@Test
	void duplicateEventIdIsSkipped() {
		UUID eventId = UUID.randomUUID();

		assertThat(inbox.firstTime(eventId, "c")).isTrue();
		assertThat(inbox.firstTime(eventId, "c")).isFalse();
	}

	@Test
	void sameEventIsFirstTimeForEachConsumer() {
		UUID eventId = UUID.randomUUID();

		assertThat(inbox.firstTime(eventId, "a")).isTrue();
		assertThat(inbox.firstTime(eventId, "b")).isTrue();
	}
}
