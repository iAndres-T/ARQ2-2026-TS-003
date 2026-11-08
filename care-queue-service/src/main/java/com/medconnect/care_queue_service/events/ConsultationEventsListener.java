package com.medconnect.care_queue_service.events;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.medconnect.care_queue_service.app.QueueService;
import com.medconnect.common.messaging.ConsumerTopology;
import com.medconnect.common.messaging.MessagingTopology;

@Component
public class ConsultationEventsListener {

	static final String CANCELLED_QUEUE = "care-queue.consultation-cancelled";

	private final EnvelopeReader reader;
	private final QueueService service;

	ConsultationEventsListener(EnvelopeReader reader, QueueService service) {
		this.reader = reader;
		this.service = service;
	}

	@Bean
	static Declarables careQueueConsultationTopology() {
		return ConsumerTopology.declarables(CANCELLED_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "consultation.cancelled");
	}

	@RabbitListener(queues = CANCELLED_QUEUE)
	public void onConsultationCancelled(Message message) {
		service.onConsultationCancelled(reader.read(message), CANCELLED_QUEUE);
	}
}
