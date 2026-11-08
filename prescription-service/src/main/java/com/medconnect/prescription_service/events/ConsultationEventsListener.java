package com.medconnect.prescription_service.events;

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
import com.medconnect.prescription_service.app.PrescriptionService;

/** Projects who is assigned to a consultation and whether it is IN_PROGRESS (queues from spec section 3). */
@Component
public class ConsultationEventsListener {

	static final String STARTED_QUEUE = "prescription.consultation-started";
	static final String COMPLETED_QUEUE = "prescription.consultation-completed";

	static final String ASSIGNED_QUEUE = "prescription.patient-assigned";
	static final String CANCELLED_QUEUE = "prescription.consultation-cancelled";
	static final String NO_SHOW_QUEUE = "prescription.consultation-no-show";

	private final EventProjector projector;
	private final PrescriptionService service;

	ConsultationEventsListener(EventProjector projector, PrescriptionService service) {
		this.projector = projector;
		this.service = service;
	}

	@Bean
	static Declarables prescriptionConsumerTopology() {
		List<Declarable> all = new ArrayList<>();
		all.addAll(ConsumerTopology.declarables(STARTED_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "consultation.started").getDeclarables());
		all.addAll(ConsumerTopology.declarables(COMPLETED_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "consultation.completed").getDeclarables());
		// additive consumer queues (spec I2): keep the projection from ASSIGNED onward
		all.addAll(ConsumerTopology.declarables(ASSIGNED_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "queue.patient-assigned").getDeclarables());
		all.addAll(ConsumerTopology.declarables(CANCELLED_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "consultation.cancelled").getDeclarables());
		all.addAll(ConsumerTopology.declarables(NO_SHOW_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "consultation.no-show").getDeclarables());
		return new Declarables(all);
	}

	@RabbitListener(queues = STARTED_QUEUE)
	public void onStarted(Message message) {
		projector.handle(message, STARTED_QUEUE,
				e -> service.consultationStarted(uuid(e, "consultationId"), uuid(e, "patientId"), uuid(e, "doctorId")));
	}

	@RabbitListener(queues = COMPLETED_QUEUE)
	public void onCompleted(Message message) {
		projector.handle(message, COMPLETED_QUEUE,
				e -> service.consultationCompleted(uuid(e, "consultationId"), uuid(e, "patientId"), uuid(e, "doctorId")));
	}

	@RabbitListener(queues = ASSIGNED_QUEUE)
	public void onAssigned(Message message) {
		projector.handle(message, ASSIGNED_QUEUE,
				e -> service.consultationAssigned(uuid(e, "consultationId"), uuid(e, "patientId"), uuid(e, "doctorId")));
	}

	@RabbitListener(queues = CANCELLED_QUEUE)
	public void onCancelled(Message message) {
		projector.handle(message, CANCELLED_QUEUE, e -> service.consultationEnded(uuid(e, "consultationId"),
				uuid(e, "patientId"), optUuid(e, "doctorId"), "CANCELLED"));
	}

	@RabbitListener(queues = NO_SHOW_QUEUE)
	public void onNoShow(Message message) {
		projector.handle(message, NO_SHOW_QUEUE, e -> service.consultationEnded(uuid(e, "consultationId"),
				uuid(e, "patientId"), optUuid(e, "doctorId"), "NO_SHOW"));
	}

	private static UUID optUuid(EventEnvelope e, String field) {
		var n = e.payload().get(field);
		return n == null || n.isNull() ? null : UUID.fromString(n.asString());
	}

	private static UUID uuid(EventEnvelope e, String field) {
		return UUID.fromString(e.payload().get(field).asString());
	}
}
