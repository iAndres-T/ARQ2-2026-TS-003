package com.medconnect.prescription_service.app;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medconnect.common.messaging.MessagingTopology;
import com.medconnect.common.messaging.OutboxWriter;
import com.medconnect.common.security.CurrentUser;
import com.medconnect.common.web.ApiException;
import com.medconnect.prescription_service.domain.ConsultationAccess;
import com.medconnect.prescription_service.domain.Prescription;
import com.medconnect.prescription_service.domain.PrescriptionItem;

import tools.jackson.databind.json.JsonMapper;

@Service
public class PrescriptionService {

	static final String EVENT_TYPE = "PrescriptionIssued";

	private final JdbcTemplate jdbc;
	private final OutboxWriter outbox;
	private final JsonMapper json;

	PrescriptionService(JdbcTemplate jdbc, OutboxWriter outbox, JsonMapper json) {
		this.jdbc = jdbc;
		this.outbox = outbox;
		this.json = json;
	}

	/** Spec section 3 PrescriptionIssued payload: only what the pharmacy needs. */
	record Issued(UUID prescriptionId, UUID consultationId, UUID patientId, UUID doctorId,
			List<PrescriptionItem> items, String notes, Instant issuedAt) {
	}

	/** Stores the prescription and its outbox event in the caller's transaction (ADR-002). */
	@Transactional
	public Prescription issue(CurrentUser user, UUID consultationId, List<PrescriptionItem> items, String notes) {
		ConsultationAccess access = access(consultationId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Consultation not found", "Unknown consultation " + consultationId));
		if (user.doctorId() == null || !user.doctorId().equals(access.doctorId())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "Not the assigned doctor", "Only the assigned doctor can prescribe");
		}
		if (!ConsultationAccess.IN_PROGRESS.equals(access.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "Consultation not in progress", "Prescriptions require an IN_PROGRESS consultation");
		}
		UUID id = UUID.randomUUID();
		Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		UUID eventId = outbox.append(MessagingTopology.PHARMACY_EXCHANGE, "prescription.issued", EVENT_TYPE, id, 1,
				user.sub(), new Issued(id, consultationId, access.patientId(), access.doctorId(), items, notes, issuedAt));
		jdbc.update("""
				insert into prescription(id, consultation_id, patient_id, doctor_id, items, notes, issued_at, event_id)
				values (?, ?, ?, ?, ?::jsonb, ?, ?, ?)""", id, consultationId, access.patientId(), access.doctorId(),
				json.writeValueAsString(items), notes, Timestamp.from(issuedAt), eventId);
		return new Prescription(id, consultationId, access.patientId(), access.doctorId(), items, notes, "PENDING", issuedAt, eventId);
	}

	/** Issuer doctor or owner patient only (spec section 2). */
	@Transactional(readOnly = true)
	public Prescription get(CurrentUser user, UUID id) {
		Prescription p = jdbc.query("select * from prescription where id = ?", this::map, id).stream().findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Prescription not found", "Unknown prescription " + id));
		boolean issuer = user.isStaff() && p.doctorId().equals(user.doctorId());
		boolean owner = user.isPatient() && p.patientId().equals(user.sub());
		if (!issuer && !owner) {
			throw new ApiException(HttpStatus.FORBIDDEN, "Access denied", "Only the issuing doctor or the patient can read it");
		}
		return p;
	}

	public void consultationAssigned(UUID consultationId, UUID patientId, UUID doctorId) {
		project(consultationId, patientId, doctorId, "ASSIGNED");
	}

	public void consultationStarted(UUID consultationId, UUID patientId, UUID doctorId) {
		project(consultationId, patientId, doctorId, ConsultationAccess.IN_PROGRESS);
	}

	public void consultationCompleted(UUID consultationId, UUID patientId, UUID doctorId) {
		project(consultationId, patientId, doctorId, ConsultationAccess.COMPLETED);
	}

	/** Cancelled before assignment has no doctor: nothing to project, POST stays 404 (never assigned to anyone). */
	public void consultationEnded(UUID consultationId, UUID patientId, UUID doctorId, String status) {
		if (doctorId != null) {
			project(consultationId, patientId, doctorId, status);
		}
	}

	/** Forward-only (ASSIGNED < IN_PROGRESS < COMPLETED < CANCELLED/NO_SHOW), so redeliveries and reordering are harmless. */
	private void project(UUID consultationId, UUID patientId, UUID doctorId, String status) {
		jdbc.update("""
				insert into consultation_access(consultation_id, patient_id, doctor_id, status) values (?, ?, ?, ?)
				on conflict (consultation_id) do update set status = excluded.status
				where case consultation_access.status when 'ASSIGNED' then 1 when 'IN_PROGRESS' then 2 when 'COMPLETED' then 3 else 4 end
				    < case excluded.status when 'ASSIGNED' then 1 when 'IN_PROGRESS' then 2 when 'COMPLETED' then 3 else 4 end
				  and consultation_access.status not in ('CANCELLED', 'NO_SHOW')""", consultationId, patientId, doctorId, status);
	}

	/** Broker confirmed the event; never overrides DEAD_LETTERED. */
	public void markPublished(UUID eventId) {
		jdbc.update("update prescription set delivery_status = 'PUBLISHED' where event_id = ? and delivery_status = 'PENDING'", eventId);
	}

	/** The pharmacy platform never accepted the event; terminal. */
	public void markDeadLettered(UUID prescriptionId) {
		jdbc.update("update prescription set delivery_status = 'DEAD_LETTERED' where id = ?", prescriptionId);
	}

	private Optional<ConsultationAccess> access(UUID consultationId) {
		return jdbc.query("select * from consultation_access where consultation_id = ?",
				(rs, i) -> new ConsultationAccess(rs.getObject("consultation_id", UUID.class), rs.getObject("patient_id", UUID.class),
						rs.getObject("doctor_id", UUID.class), rs.getString("status")), consultationId).stream().findFirst();
	}

	private Prescription map(ResultSet rs, int row) throws SQLException {
		return new Prescription(rs.getObject("id", UUID.class), rs.getObject("consultation_id", UUID.class),
				rs.getObject("patient_id", UUID.class), rs.getObject("doctor_id", UUID.class),
				json.readerForListOf(PrescriptionItem.class).readValue(rs.getString("items")), rs.getString("notes"),
				rs.getString("delivery_status"), rs.getTimestamp("issued_at").toInstant(), rs.getObject("event_id", UUID.class));
	}
}
