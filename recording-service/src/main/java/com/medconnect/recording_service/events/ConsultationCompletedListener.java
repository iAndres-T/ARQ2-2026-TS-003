package com.medconnect.recording_service.events;

import java.util.UUID;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.medconnect.common.messaging.ConsumerTopology;
import com.medconnect.common.messaging.EventEnvelope;
import com.medconnect.common.messaging.InboxGuard;
import com.medconnect.common.messaging.MessagingTopology;

import tools.jackson.databind.json.JsonMapper;

@Component
public class ConsultationCompletedListener {

	static final String QUEUE = "recording.consultation-completed";

	private final JsonMapper json;
	private final InboxGuard inbox;
	private final JdbcTemplate jdbc;

	ConsultationCompletedListener(JsonMapper json, InboxGuard inbox, JdbcTemplate jdbc) {
		this.json = json;
		this.inbox = inbox;
		this.jdbc = jdbc;
	}

	@Bean
	static Declarables recordingTopology() {
		return ConsumerTopology.declarables(QUEUE, MessagingTopology.EVENTS_EXCHANGE, "consultation.completed");
	}

	/** Alert queue for operations (spec §3); no consumer in this service, Prometheus watches its depth. */
	@Bean
	static Declarables opsRecordingFailedTopology() {
		return ConsumerTopology.declarables("ops.recording-failed", MessagingTopology.EVENTS_EXCHANGE, "recording.failed");
	}

	@RabbitListener(queues = QUEUE)
	@Transactional
	public void onConsultationCompleted(Message message) {
		EventEnvelope e = json.readValue(message.getBody(), EventEnvelope.class);
		if (!inbox.firstTime(e.eventId(), QUEUE)) {
			return;
		}
		String sessionId = e.payload().get("sessionId").asString();
		jdbc.update("insert into session_map(session_id, consultation_id, doctor_id, patient_id) values (?, ?, ?, ?) on conflict do nothing",
				sessionId, UUID.fromString(e.payload().get("consultationId").asString()), uuid(e, "doctorId"), uuid(e, "patientId"));
	}

	private static UUID uuid(EventEnvelope e, String field) {
		var n = e.payload().get(field);
		return n == null || n.isNull() ? null : UUID.fromString(n.asString());
	}
}
