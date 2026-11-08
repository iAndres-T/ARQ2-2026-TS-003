package com.medconnect.scheduling_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import com.medconnect.scheduling_service.events.ConsultationEventsListener;
import com.medconnect.scheduling_service.jobs.ShiftEndJob;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@Import(TestContainers.class)
class AvailabilityIT extends ApiSupport {

	@Autowired
	ConsultationEventsListener listener;
	@Autowired
	ShiftEndJob shiftEndJob;
	@Autowired
	RabbitTemplate rabbit;

	UUID freeDoctor() throws Exception {
		UUID d = UUID.randomUUID();
		activeShift(d);
		assertThat(setStatus(d, "FREE").getStatus()).isEqualTo(200);
		return d;
	}

	String assigned(UUID consultation, UUID doctor) {
		return envelope("PatientAssigned", consultation, doctor, ",\"priority\":\"HIGH\",\"assignedAt\":\"2030-01-01T08:00:00Z\"");
	}

	@Test
	void freeWithoutActiveShiftIs409() throws Exception {
		UUID d = UUID.randomUUID();
		assertThat(setStatus(d, "FREE").getStatus()).isEqualTo(409); // no shift at all
		Instant later = Instant.now().plus(5, ChronoUnit.HOURS);
		createShift(d, later, later.plus(1, ChronoUnit.HOURS), admin());
		assertThat(setStatus(d, "FREE").getStatus()).isEqualTo(409); // shift not started
	}

	@Test // S-F4-11
	void freeEmitsDoctorBecameAvailable() throws Exception {
		UUID d = freeDoctor();
		JsonNode a = availability(d);
		assertThat(a.get("status").asString()).isEqualTo("FREE");
		assertThat(a.get("shiftId").asString()).isNotBlank();
		JsonNode e = outbox(d, "DoctorBecameAvailable").get(0);
		assertThat(e.get("payload").get("doctorId").asString()).isEqualTo(d.toString());
		assertThat(e.get("payload").get("shiftId").asString()).isEqualTo(a.get("shiftId").asString());
		assertThat(e.get("payload").get("availableSince").asString()).isNotBlank();
		// re-announce: PUT FREE while already FREE re-emits with a higher version (heals a lost care-queue Redis)
		assertThat(setStatus(d, "FREE").getStatus()).isEqualTo(200);
		List<JsonNode> events = outbox(d, "DoctorBecameAvailable");
		assertThat(events).hasSize(2);
		assertThat(events.get(1).get("aggregateVersion").asInt()).isGreaterThan(events.get(0).get("aggregateVersion").asInt());
	}

	@Test // end event overtook the assignment: care-queue already marked the doctor BUSY, so FREE must be re-announced
	void assignmentForAlreadyEndedConsultationReannouncesFreeDoctor() throws Exception {
		UUID d = freeDoctor();
		UUID c = UUID.randomUUID();
		listener.onConsultationCancelled(msg(envelope("ConsultationCancelled", c, null, ",\"statusAtCancellation\":\"WAITING\"")));
		listener.onPatientAssigned(msg(assigned(c, d)));
		assertThat(status(d)).isEqualTo("FREE");
		List<JsonNode> events = outbox(d, "DoctorBecameAvailable");
		assertThat(events).hasSize(2);
		assertThat(events.get(1).get("aggregateVersion").asInt()).isGreaterThan(events.get(0).get("aggregateVersion").asInt());
	}

	@Test
	void busyRejectedAsInput() throws Exception {
		UUID d = freeDoctor();
		assertThat(setStatus(d, "BUSY").getStatus()).isEqualTo(422);
		assertThat(setStatus(d, "NOPE").getStatus()).isEqualTo(422);
	}

	@Test
	void doctorWithoutDoctorIdClaimIs403() throws Exception {
		assertThat(mvc.perform(put("/api/v1/doctors/me/availability").with(doctor(null)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"FREE\"}")).andReturn().getResponse().getStatus()).isEqualTo(403);
	}

	@Test // S-F4-07
	void patientAssignedMakesBusyAndEmitsUnavailable() throws Exception {
		UUID d = freeDoctor();
		listener.onPatientAssigned(msg(assigned(UUID.randomUUID(), d)));
		assertThat(status(d)).isEqualTo("BUSY");
		JsonNode e = outbox(d, "DoctorBecameUnavailable").get(0);
		assertThat(e.get("payload").get("status").asString()).isEqualTo("BUSY");
		assertThat(e.get("aggregateVersion").asInt()).isEqualTo(2); // FREE was version 1
	}

	@Test // S-F4-11
	void completedReturnsDoctorToFree() throws Exception {
		UUID d = freeDoctor();
		UUID c = UUID.randomUUID();
		listener.onPatientAssigned(msg(assigned(c, d)));
		listener.onConsultationCompleted(msg(envelope("ConsultationCompleted", c, d, "")));
		assertThat(status(d)).isEqualTo("FREE");
		assertThat(outbox(d, "DoctorBecameAvailable")).hasSize(2);
	}

	@Test
	void noShowAndCancelledWithDoctorReturnDoctorToFree() throws Exception {
		UUID d = freeDoctor();
		UUID c = UUID.randomUUID();
		listener.onPatientAssigned(msg(assigned(c, d)));
		listener.onNoShow(msg(envelope("ConsultationNoShow", c, d, "")));
		assertThat(status(d)).isEqualTo("FREE");
		UUID c2 = UUID.randomUUID();
		listener.onPatientAssigned(msg(assigned(c2, d)));
		listener.onConsultationCancelled(msg(envelope("ConsultationCancelled", c2, d, ",\"statusAtCancellation\":\"ASSIGNED\"")));
		assertThat(status(d)).isEqualTo("FREE");
	}

	@Test
	void lateEventForOtherConsultationDoesNotFreeBusyDoctor() throws Exception {
		UUID d = freeDoctor();
		UUID old = UUID.randomUUID();
		listener.onPatientAssigned(msg(assigned(old, d)));
		listener.onConsultationCompleted(msg(envelope("ConsultationCompleted", old, d, "")));
		listener.onPatientAssigned(msg(assigned(UUID.randomUUID(), d)));
		// duplicate completion (new eventId) of the old consultation arrives late
		listener.onConsultationCompleted(msg(envelope("ConsultationCompleted", old, d, "")));
		assertThat(status(d)).isEqualTo("BUSY");
		// cancellation without a doctor is ignored
		listener.onConsultationCancelled(msg(envelope("ConsultationCancelled", UUID.randomUUID(), null, ",\"statusAtCancellation\":\"WAITING\"")));
		assertThat(status(d)).isEqualTo("BUSY");
	}

	@Test // out-of-order delivery across queues: end event consumed before PatientAssigned
	void endEventBeforePatientAssignedDoesNotLeaveDoctorBusy() throws Exception {
		UUID d = freeDoctor();
		UUID c = UUID.randomUUID();
		listener.onConsultationCompleted(msg(envelope("ConsultationCompleted", c, d, "")));
		listener.onPatientAssigned(msg(assigned(c, d)));
		assertThat(status(d)).isEqualTo("FREE");
		assertThat(outbox(d, "DoctorBecameUnavailable")).isEmpty();
	}

	@Test // interleaving of the two consumers must never leave the doctor BUSY for an ended consultation
	void concurrentEndAndAssignNeverLeavesDoctorBusy() throws Exception {
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			for (int i = 0; i < 40; i++) {
				UUID d = freeDoctor();
				UUID c = UUID.randomUUID();
				String end = envelope("ConsultationCompleted", c, d, "");
				String assign = assigned(c, d);
				var go = new java.util.concurrent.CountDownLatch(1);
				var f1 = pool.submit(() -> { go.await(); listener.onConsultationCompleted(msg(end)); return null; });
				var f2 = pool.submit(() -> { go.await(); listener.onPatientAssigned(msg(assign)); return null; });
				go.countDown();
				f1.get();
				f2.get();
				assertThat(status(d)).as("iteration %d", i).isNotEqualTo("BUSY");
			}
		}
		finally {
			pool.shutdown();
		}
	}

	@Test // patient cancels while WAITING at the moment a doctor claims them: cancel carries no doctorId
	void cancelWithoutDoctorIdFreesDoctorAssignedBeforehand() throws Exception {
		UUID d = freeDoctor();
		UUID c = UUID.randomUUID();
		listener.onPatientAssigned(msg(assigned(c, d)));
		listener.onConsultationCancelled(msg(envelope("ConsultationCancelled", c, null, ",\"statusAtCancellation\":\"WAITING\"")));
		assertThat(status(d)).isEqualTo("FREE");
		assertThat(outbox(d, "DoctorBecameAvailable")).hasSize(2);
	}

	@Test
	void cancelWithoutDoctorIdAfterShiftEndedMakesDoctorOffline() throws Exception {
		UUID d = freeDoctor();
		UUID c = UUID.randomUUID();
		listener.onPatientAssigned(msg(assigned(c, d)));
		jdbc.update("update shifts set ends_at = now() - interval '1 minute' where doctor_id = ?", d);
		listener.onConsultationCancelled(msg(envelope("ConsultationCancelled", c, null, ",\"statusAtCancellation\":\"WAITING\"")));
		assertThat(status(d)).isEqualTo("OFFLINE");
		assertThat(outbox(d, "DoctorBecameUnavailable").get(1).get("payload").get("status").asString()).isEqualTo("OFFLINE");
	}

	@Test
	void completedAfterShiftEndedGoesOffline() throws Exception {
		UUID d = freeDoctor();
		UUID c = UUID.randomUUID();
		listener.onPatientAssigned(msg(assigned(c, d)));
		jdbc.update("update shifts set ends_at = now() - interval '1 minute' where doctor_id = ?", d);
		listener.onConsultationCompleted(msg(envelope("ConsultationCompleted", c, d, "")));
		assertThat(status(d)).isEqualTo("OFFLINE");
		assertThat(outbox(d, "DoctorBecameUnavailable").get(1).get("payload").get("status").asString()).isEqualTo("OFFLINE");
	}

	@Test
	void offlineWhileBusyIs409() throws Exception {
		UUID d = freeDoctor();
		listener.onPatientAssigned(msg(assigned(UUID.randomUUID(), d)));
		assertThat(setStatus(d, "OFFLINE").getStatus()).isEqualTo(409);
		assertThat(setStatus(d, "FREE").getStatus()).isEqualTo(409);
	}

	@Test
	void offlineEmitsUnavailable() throws Exception {
		UUID d = freeDoctor();
		assertThat(setStatus(d, "OFFLINE").getStatus()).isEqualTo(200);
		assertThat(outbox(d, "DoctorBecameUnavailable").get(0).get("payload").get("status").asString()).isEqualTo("OFFLINE");
	}

	@Test
	void shiftEndJobSendsFreeDoctorsWithoutActiveShiftOffline() throws Exception {
		UUID d = freeDoctor();
		UUID busy = freeDoctor();
		listener.onPatientAssigned(msg(assigned(UUID.randomUUID(), busy)));
		jdbc.update("update shifts set ends_at = now() - interval '1 minute' where doctor_id in (?, ?)", d, busy);
		shiftEndJob.run();
		assertThat(status(d)).isEqualTo("OFFLINE");
		assertThat(outbox(d, "DoctorBecameUnavailable")).hasSize(1);
		assertThat(status(busy)).isEqualTo("BUSY");
	}

	@Test // real broker: queue.patient-assigned on medconnect.events -> scheduling.patient-assigned
	void patientAssignedThroughRabbitMakesBusy() throws Exception {
		UUID d = freeDoctor();
		rabbit.send("medconnect.events", "queue.patient-assigned", msg(assigned(UUID.randomUUID(), d)));
		long deadline = System.currentTimeMillis() + 20_000;
		while (System.currentTimeMillis() < deadline && !"BUSY".equals(status(d))) {
			Thread.sleep(200);
		}
		assertThat(status(d)).isEqualTo("BUSY");
		assertThat(outbox(d, "DoctorBecameUnavailable")).hasSize(1);
	}
}
