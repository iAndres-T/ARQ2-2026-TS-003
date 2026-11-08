package com.medconnect.scheduling_service.events;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.medconnect.common.messaging.ConsumerTopology;
import com.medconnect.common.messaging.EventEnvelope;
import com.medconnect.common.messaging.MessagingTopology;
import com.medconnect.scheduling_service.availability.AvailabilityService;

import tools.jackson.databind.JsonNode;

/** PatientAssigned makes the doctor BUSY; Completed / NoShow / Cancelled end the doctor's current consultation. */
@Component
public class ConsultationEventsListener {

	static final String ASSIGNED_QUEUE = "scheduling.patient-assigned";
	static final String COMPLETED_QUEUE = "scheduling.consultation-completed";
	static final String CANCELLED_QUEUE = "scheduling.consultation-cancelled";
	static final String NO_SHOW_QUEUE = "scheduling.consultation-no-show";

	private final EventProjector projector;
	private final AvailabilityService service;

	ConsultationEventsListener(EventProjector projector, AvailabilityService service) {
		this.projector = projector;
		this.service = service;
	}

	@Bean
	static Declarables schedulingConsumerTopology() {
		List<Declarable> all = new ArrayList<>();
		String ex = MessagingTopology.EVENTS_EXCHANGE;
		all.addAll(ConsumerTopology.declarables(ASSIGNED_QUEUE, ex, "queue.patient-assigned").getDeclarables());
		all.addAll(ConsumerTopology.declarables(COMPLETED_QUEUE, ex, "consultation.completed").getDeclarables());
		all.addAll(ConsumerTopology.declarables(CANCELLED_QUEUE, ex, "consultation.cancelled").getDeclarables());
		all.addAll(ConsumerTopology.declarables(NO_SHOW_QUEUE, ex, "consultation.no-show").getDeclarables());
		return new Declarables(all);
	}

	@RabbitListener(queues = ASSIGNED_QUEUE)
	public void onPatientAssigned(Message message) {
		projector.handle(message, ASSIGNED_QUEUE, e -> service.onPatientAssigned(uuid(e, "doctorId"), uuid(e, "consultationId")));
	}

	@RabbitListener(queues = COMPLETED_QUEUE)
	public void onConsultationCompleted(Message message) {
		projector.handle(message, COMPLETED_QUEUE, this::ended);
	}

	@RabbitListener(queues = CANCELLED_QUEUE)
	public void onConsultationCancelled(Message message) {
		projector.handle(message, CANCELLED_QUEUE, this::ended);
	}

	@RabbitListener(queues = NO_SHOW_QUEUE)
	public void onNoShow(Message message) {
		projector.handle(message, NO_SHOW_QUEUE, this::ended);
	}

	private void ended(EventEnvelope e) {
		service.onConsultationEnded(uuid(e, "doctorId"), uuid(e, "consultationId"));
	}

	private static UUID uuid(EventEnvelope e, String field) {
		JsonNode n = e.payload().get(field);
		return (n == null || n.isNull()) ? null : UUID.fromString(n.asString());
	}
}
