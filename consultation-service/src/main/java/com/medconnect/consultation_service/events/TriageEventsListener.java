package com.medconnect.consultation_service.events;

import java.util.UUID;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.medconnect.common.messaging.ConsumerTopology;
import com.medconnect.common.messaging.MessagingTopology;
import com.medconnect.consultation_service.app.ConsultationService;
import com.medconnect.consultation_service.domain.Priority;

@Component
public class TriageEventsListener {

	static final String TRIAGED_QUEUE = "consultation.patient-triaged";
	static final String OVERRIDDEN_QUEUE = "consultation.priority-overridden";

	private final EventProjector projector;
	private final ConsultationService service;

	TriageEventsListener(EventProjector projector, ConsultationService service) {
		this.projector = projector;
		this.service = service;
	}

	@Bean
	static Declarables consultationTriageTopology() {
		Declarables triaged = ConsumerTopology.declarables(TRIAGED_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "triage.patient-triaged");
		Declarables overridden = ConsumerTopology.declarables(OVERRIDDEN_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "triage.priority-overridden");
		java.util.List<org.springframework.amqp.core.Declarable> all = new java.util.ArrayList<>(triaged.getDeclarables());
		all.addAll(overridden.getDeclarables());
		return new Declarables(all);
	}

	@RabbitListener(queues = TRIAGED_QUEUE)
	public void onPatientTriaged(Message message) {
		projector.handle(message, TRIAGED_QUEUE, e -> service.onPatientTriaged(
				UUID.fromString(e.payload().get("consultationId").asString()),
				Priority.valueOf(e.payload().get("priority").asString()), e.payload().get("triageVersion").asInt()));
	}

	@RabbitListener(queues = OVERRIDDEN_QUEUE)
	public void onPriorityOverridden(Message message) {
		projector.handle(message, OVERRIDDEN_QUEUE, e -> service.onPriorityOverridden(
				UUID.fromString(e.payload().get("consultationId").asString()),
				Priority.valueOf(e.payload().get("newPriority").asString()), e.payload().get("triageVersion").asInt()));
	}
}
