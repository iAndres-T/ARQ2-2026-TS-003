package com.medconnect.triage_service.events;

import java.time.Instant;
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
import com.medconnect.triage_service.app.TriageService;
import com.medconnect.triage_service.domain.SymptomReport;
import com.medconnect.triage_service.domain.TriageRecord.Status;

import tools.jackson.databind.json.JsonMapper;

/** Consumes ConsultationRequested / SymptomsUpdated and, for the D7 status projection, PatientAssigned / ConsultationCancelled. */
@Component
public class ConsultationEventsListener {

	static final String REQUESTED_QUEUE = "triage.consultation-requested";
	static final String UPDATED_QUEUE = "triage.symptoms-updated";
	static final String ASSIGNED_QUEUE = "triage.patient-assigned";
	static final String CANCELLED_QUEUE = "triage.consultation-cancelled";

	private final EventProjector projector;
	private final TriageService service;
	private final JsonMapper json;

	ConsultationEventsListener(EventProjector projector, TriageService service, JsonMapper json) {
		this.projector = projector;
		this.service = service;
		this.json = json;
	}

	@Bean
	static Declarables triageConsumerTopology() {
		List<Declarable> all = new ArrayList<>();
		String ex = MessagingTopology.EVENTS_EXCHANGE;
		all.addAll(ConsumerTopology.declarables(REQUESTED_QUEUE, ex, "consultation.requested").getDeclarables());
		all.addAll(ConsumerTopology.declarables(UPDATED_QUEUE, ex, "consultation.symptoms-updated").getDeclarables());
		all.addAll(ConsumerTopology.declarables(ASSIGNED_QUEUE, ex, "queue.patient-assigned").getDeclarables());
		all.addAll(ConsumerTopology.declarables(CANCELLED_QUEUE, ex, "consultation.cancelled").getDeclarables());
		return new Declarables(all);
	}

	@RabbitListener(queues = REQUESTED_QUEUE)
	public void onConsultationRequested(Message message) {
		projector.handle(message, REQUESTED_QUEUE, e -> service.onConsultationRequested(id(e),
				UUID.fromString(e.payload().get("patientId").asString()), symptoms(e),
				Instant.parse(e.payload().get("requestedAt").asString()), e.aggregateVersion()));
	}

	@RabbitListener(queues = UPDATED_QUEUE)
	public void onSymptomsUpdated(Message message) {
		projector.handle(message, UPDATED_QUEUE, e -> service.onSymptomsUpdated(id(e), symptoms(e), e.aggregateVersion()));
	}

	@RabbitListener(queues = ASSIGNED_QUEUE)
	public void onPatientAssigned(Message message) {
		projector.handle(message, ASSIGNED_QUEUE, e -> service.project(id(e), Status.ASSIGNED));
	}

	@RabbitListener(queues = CANCELLED_QUEUE)
	public void onConsultationCancelled(Message message) {
		projector.handle(message, CANCELLED_QUEUE, e -> service.project(id(e), Status.CANCELLED));
	}

	private static UUID id(EventEnvelope e) {
		return UUID.fromString(e.payload().get("consultationId").asString());
	}

	private SymptomReport symptoms(EventEnvelope e) {
		return json.treeToValue(e.payload().get("symptoms"), SymptomReport.class);
	}
}
