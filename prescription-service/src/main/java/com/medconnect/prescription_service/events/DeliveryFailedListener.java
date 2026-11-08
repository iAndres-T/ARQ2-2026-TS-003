package com.medconnect.prescription_service.events;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.medconnect.common.messaging.ConsumerTopology;
import com.medconnect.common.messaging.MessagingTopology;
import com.medconnect.prescription_service.app.PrescriptionService;

/** D8: the dead-lettered PrescriptionIssued arrives from the pharmacy DLX fanout; mark the prescription and alert. */
@Component
public class DeliveryFailedListener {

	static final String QUEUE = "prescription.delivery-failed";
	private static final Logger log = LoggerFactory.getLogger(DeliveryFailedListener.class);

	private final EventProjector projector;
	private final PrescriptionService service;

	DeliveryFailedListener(EventProjector projector, PrescriptionService service) {
		this.projector = projector;
		this.service = service;
	}

	/** Same queue pharmacy-provider declares; the fanout ignores the routing key. */
	@Bean
	static Declarables deliveryFailedTopology() {
		return ConsumerTopology.declarables(QUEUE, MessagingTopology.PHARMACY_DLX_EXCHANGE, "#");
	}

	@RabbitListener(queues = QUEUE)
	public void onDeadLettered(Message message) {
		projector.handle(message, QUEUE, e -> {
			UUID prescriptionId = UUID.fromString(e.payload().get("prescriptionId").asString());
			service.markDeadLettered(prescriptionId);
			log.error("ALERT prescription delivery dead-lettered prescriptionId={}", prescriptionId);
		});
	}
}
