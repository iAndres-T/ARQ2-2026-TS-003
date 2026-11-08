package com.medconnect.care_queue_service.events;

import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.medconnect.care_queue_service.app.QueueService;
import com.medconnect.common.messaging.ConsumerTopology;
import com.medconnect.common.messaging.MessagingTopology;

@Component
public class TriageEventsListener {

	static final String TRIAGED_QUEUE = "care-queue.patient-triaged";
	static final String OVERRIDDEN_QUEUE = "care-queue.priority-overridden";

	private final EnvelopeReader reader;
	private final QueueService service;

	TriageEventsListener(EnvelopeReader reader, QueueService service) {
		this.reader = reader;
		this.service = service;
	}

	@Bean
	static Declarables careQueueTriageTopology() {
		List<Declarable> all = new ArrayList<>(ConsumerTopology
			.declarables(TRIAGED_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "triage.patient-triaged").getDeclarables());
		all.addAll(ConsumerTopology
			.declarables(OVERRIDDEN_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "triage.priority-overridden").getDeclarables());
		return new Declarables(all);
	}

	@RabbitListener(queues = TRIAGED_QUEUE)
	public void onPatientTriaged(Message message) {
		service.onPatientTriaged(reader.read(message), TRIAGED_QUEUE);
	}

	@RabbitListener(queues = OVERRIDDEN_QUEUE)
	public void onPriorityOverridden(Message message) {
		service.onPriorityOverridden(reader.read(message), OVERRIDDEN_QUEUE);
	}
}
