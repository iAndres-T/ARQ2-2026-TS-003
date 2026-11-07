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
public class QueueEventsListener {

	static final String ENQUEUED_QUEUE = "consultation.patient-enqueued";

	private final EventProjector projector;
	private final ConsultationService service;

	QueueEventsListener(EventProjector projector, ConsultationService service) {
		this.projector = projector;
		this.service = service;
	}

	@Bean
	static Declarables consultationEnqueuedTopology() {
		return ConsumerTopology.declarables(ENQUEUED_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "queue.patient-enqueued");
	}

	@RabbitListener(queues = ENQUEUED_QUEUE)
	public void onPatientEnqueued(Message message) {
		projector.handle(message, ENQUEUED_QUEUE, e -> service.onPatientEnqueued(
				UUID.fromString(e.payload().get("consultationId").asString()),
				Priority.valueOf(e.payload().get("queue").asString()), e.payload().get("triageVersion").asInt()));
	}
}
