package com.medconnect.consultation_service.events;

import java.time.Instant;
import java.util.UUID;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.medconnect.common.messaging.ConsumerTopology;
import com.medconnect.common.messaging.EventEnvelope;
import com.medconnect.common.messaging.MessagingTopology;
import com.medconnect.consultation_service.app.ConsultationService;

import tools.jackson.databind.json.JsonMapper;

/**
 * PatientAssigned (S-F4-07): commits ASSIGNED only. The video session is created lazily on join/start (I1), so a
 * provider outage never strands a claimed patient or doctor.
 */
@Component
public class AssignmentListener {

	static final String ASSIGNED_QUEUE = "consultation.patient-assigned";

	private final EventProjector projector;
	private final ConsultationService service;
	private final JsonMapper json;

	AssignmentListener(EventProjector projector, ConsultationService service, JsonMapper json) {
		this.projector = projector;
		this.service = service;
		this.json = json;
	}

	@Bean
	static Declarables consultationAssignedTopology() {
		return ConsumerTopology.declarables(ASSIGNED_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "queue.patient-assigned");
	}

	@RabbitListener(queues = ASSIGNED_QUEUE)
	public void onPatientAssigned(Message message) {
		EventEnvelope envelope = json.readValue(message.getBody(), EventEnvelope.class);
		UUID id = UUID.fromString(envelope.payload().required("consultationId").asString());
		projector.handle(message, ASSIGNED_QUEUE, e -> service.onPatientAssigned(id,
				UUID.fromString(e.payload().required("doctorId").asString()),
				Instant.parse(e.payload().required("assignedAt").asString())));
	}
}
