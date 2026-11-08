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
public class DoctorEventsListener {

	static final String AVAILABLE_QUEUE = "care-queue.doctor-became-available";
	static final String UNAVAILABLE_QUEUE = "care-queue.doctor-became-unavailable";

	private final EnvelopeReader reader;
	private final QueueService service;

	DoctorEventsListener(EnvelopeReader reader, QueueService service) {
		this.reader = reader;
		this.service = service;
	}

	@Bean
	static Declarables careQueueDoctorTopology() {
		List<Declarable> all = new ArrayList<>(ConsumerTopology
			.declarables(AVAILABLE_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "doctor.became-available").getDeclarables());
		all.addAll(ConsumerTopology
			.declarables(UNAVAILABLE_QUEUE, MessagingTopology.EVENTS_EXCHANGE, "doctor.became-unavailable").getDeclarables());
		return new Declarables(all);
	}

	@RabbitListener(queues = AVAILABLE_QUEUE)
	public void onDoctorBecameAvailable(Message message) {
		service.onDoctorBecameAvailable(reader.read(message), AVAILABLE_QUEUE);
	}

	@RabbitListener(queues = UNAVAILABLE_QUEUE)
	public void onDoctorBecameUnavailable(Message message) {
		service.onDoctorBecameUnavailable(reader.read(message), UNAVAILABLE_QUEUE);
	}
}
