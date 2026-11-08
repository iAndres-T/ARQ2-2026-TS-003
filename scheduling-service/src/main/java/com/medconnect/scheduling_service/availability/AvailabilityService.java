package com.medconnect.scheduling_service.availability;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medconnect.common.messaging.MessagingTopology;
import com.medconnect.common.messaging.OutboxWriter;
import com.medconnect.common.web.ApiException;
import com.medconnect.scheduling_service.shift.Shift;

/**
 * Shifts and the FREE/BUSY/OFFLINE state machine. The doctor_availability row is the per-doctor lock: every
 * mutation takes it with {@code for update}, which serialises shift overlap checks and status changes.
 */
@Service
public class AvailabilityService {

	public static final String FREE = "FREE";
	public static final String BUSY = "BUSY";
	public static final String OFFLINE = "OFFLINE";

	private record Row(UUID doctorId, String status, Instant since, UUID shiftId, UUID consultationId, long version) {
	}

	private final JdbcTemplate jdbc;
	private final OutboxWriter outbox;

	AvailabilityService(JdbcTemplate jdbc, OutboxWriter outbox) {
		this.jdbc = jdbc;
		this.outbox = outbox;
	}

	// ---- shifts

	@Transactional
	public Shift createShift(UUID doctorId, Instant startsAt, Instant endsAt) {
		if (!endsAt.isAfter(startsAt)) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid shift", "endsAt must be after startsAt");
		}
		jdbc.update("insert into doctor_availability(doctor_id, status, since) values (?, 'OFFLINE', now()) on conflict do nothing", doctorId);
		lock(doctorId);
		Integer overlaps = jdbc.queryForObject("select count(*) from shifts where doctor_id = ? and starts_at < ? and ends_at > ?",
				Integer.class, doctorId, ts(endsAt), ts(startsAt));
		if (overlaps != null && overlaps > 0) {
			throw new ApiException(HttpStatus.CONFLICT, "Shift overlap", "The doctor already has a shift in that interval");
		}
		UUID id = UUID.randomUUID();
		jdbc.update("insert into shifts(shift_id, doctor_id, starts_at, ends_at) values (?, ?, ?, ?)", id, doctorId, ts(startsAt), ts(endsAt));
		return new Shift(id, doctorId, startsAt, endsAt);
	}

	public List<Shift> shifts(Instant from, Instant to, UUID doctorId) {
		return jdbc.query("select * from shifts where starts_at < ? and ends_at > ? and (?::uuid is null or doctor_id = ?::uuid) order by starts_at, shift_id",
				(rs, i) -> new Shift(rs.getObject("shift_id", UUID.class), rs.getObject("doctor_id", UUID.class), instant(rs, "starts_at"),
						instant(rs, "ends_at")), ts(to), ts(from), doctorId, doctorId);
	}

	// ---- availability queries

	/** Page ordered by doctorId; the cursor is the last doctorId of the previous page. */
	public List<DoctorAvailability> list(String status, UUID after, int limit) {
		return jdbc.query("select * from doctor_availability where (?::text is null or status = ?) and (?::uuid is null or doctor_id > ?::uuid) order by doctor_id limit ?",
				(rs, i) -> view(rs), status, status, after, after, limit);
	}

	// ---- doctor-driven changes

	@Transactional
	public DoctorAvailability setStatus(UUID doctorId, String status, UUID actorId) {
		if (!FREE.equals(status) && !OFFLINE.equals(status)) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid status", "Only FREE or OFFLINE can be requested");
		}
		Row r = lock(doctorId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "No shift", "The doctor has no shifts"));
		if (r.status().equals(status) && !FREE.equals(status)) { // FREE -> FREE re-announces (version bump) below
			return view(r);
		}
		if (BUSY.equals(r.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "Doctor is busy", "Availability cannot change while a consultation is in progress");
		}
		if (OFFLINE.equals(status)) {
			return transition(r, OFFLINE, null, null, actorId);
		}
		UUID shift = activeShift(doctorId)
			.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "No active shift", "The doctor has no active shift"));
		return transition(r, FREE, shift, null, actorId);
	}

	// ---- event-driven changes

	@Transactional
	public void onPatientAssigned(UUID doctorId, UUID consultationId) {
		lockConsultation(consultationId);
		lock(doctorId).ifPresent(r -> {
			// checked under the doctor lock: the end event records its id under the same lock
			if (jdbc.queryForObject("select count(*) from ended_consultations where consultation_id = ?", Integer.class, consultationId) > 0) {
				if (FREE.equals(r.status())) { // care-queue already marked the doctor BUSY locally: heal its projection
					transition(r, FREE, r.shiftId(), null, null);
				}
				return; // the end event overtook this assignment
			}
			if (BUSY.equals(r.status())) { // second assignment while busy: track the newest consultation, no event
				jdbc.update("update doctor_availability set current_consultation_id = ? where doctor_id = ?", consultationId, doctorId);
			}
			else {
				transition(r, BUSY, r.shiftId(), consultationId, null);
			}
		});
	}

	/** Completed / NoShow / Cancelled: only the doctor's current consultation frees them. */
	@Transactional
	public void onConsultationEnded(UUID doctorId, UUID consultationId) {
		lockConsultation(consultationId);
		UUID doctor = doctorId;
		if (doctor == null) {
			// A WAITING cancel carries no doctorId, but a claim may have raced it: find who (if anyone) holds this consultation.
			markEnded(consultationId);
			doctor = jdbc.queryForList("select doctor_id from doctor_availability where current_consultation_id = ? limit 1", UUID.class,
					consultationId).stream().findFirst().orElse(null);
			if (doctor == null) {
				return;
			}
		}
		Optional<Row> locked = lock(doctor); // lock first, then record, so PatientAssigned sees either both or neither
		markEnded(consultationId);
		locked.filter(r -> BUSY.equals(r.status()) && consultationId.equals(r.consultationId())).ifPresent(r -> {
			Optional<UUID> shift = activeShift(r.doctorId());
			if (shift.isPresent()) {
				transition(r, FREE, shift.get(), null, null);
			}
			else {
				transition(r, OFFLINE, null, null, null);
			}
		});
	}

	@Transactional
	public int expireFreeWithoutActiveShift() {
		Instant now = Instant.now();
		List<UUID> doctors = jdbc.queryForList("""
				select doctor_id from doctor_availability a where status = 'FREE'
				  and not exists (select 1 from shifts s where s.doctor_id = a.doctor_id and s.starts_at <= ? and ? < s.ends_at)
				order by doctor_id""", UUID.class, ts(now), ts(now));
		for (UUID d : doctors) { // re-check under the row lock: the doctor may have changed meanwhile
			lock(d).filter(r -> FREE.equals(r.status()) && activeShift(d).isEmpty())
				.ifPresent(r -> transition(r, OFFLINE, null, null, null));
		}
		return doctors.size();
	}

	// ---- internals

	/** Lock order everywhere: consultation advisory lock first, then the doctor row lock (avoids deadlocks). */
	private void lockConsultation(UUID consultationId) {
		jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
		}, consultationId.toString());
	}

	private void markEnded(UUID consultationId) {
		jdbc.update("insert into ended_consultations(consultation_id) values (?) on conflict do nothing", consultationId);
	}

	private DoctorAvailability transition(Row r, String status, UUID shiftId, UUID consultationId, UUID actorId) {
		Instant now = Instant.now();
		long version = r.version() + 1;
		jdbc.update("update doctor_availability set status = ?, since = ?, shift_id = ?, current_consultation_id = ?, version = ? where doctor_id = ?",
				status, ts(now), shiftId, consultationId, version, r.doctorId());
		if (FREE.equals(status)) {
			outbox.append(MessagingTopology.EVENTS_EXCHANGE, "doctor.became-available", "DoctorBecameAvailable", r.doctorId(), version, actorId,
					Map.of("doctorId", r.doctorId(), "shiftId", shiftId, "availableSince", now));
		}
		else {
			outbox.append(MessagingTopology.EVENTS_EXCHANGE, "doctor.became-unavailable", "DoctorBecameUnavailable", r.doctorId(), version, actorId,
					Map.of("doctorId", r.doctorId(), "status", status, "since", now));
		}
		return new DoctorAvailability(r.doctorId(), status, now, shiftId);
	}

	private Optional<Row> lock(UUID doctorId) {
		return jdbc.query("select * from doctor_availability where doctor_id = ? for update",
				(rs, i) -> new Row(doctorId, rs.getString("status"), instant(rs, "since"), rs.getObject("shift_id", UUID.class),
						rs.getObject("current_consultation_id", UUID.class), rs.getLong("version")),
				doctorId).stream().findFirst();
	}

	private Optional<UUID> activeShift(UUID doctorId) {
		Instant now = Instant.now();
		return jdbc.queryForList("select shift_id from shifts where doctor_id = ? and starts_at <= ? and ? < ends_at limit 1", UUID.class,
				doctorId, ts(now), ts(now)).stream().findFirst();
	}

	private static DoctorAvailability view(Row r) {
		return new DoctorAvailability(r.doctorId(), r.status(), r.since(), r.shiftId());
	}

	private static DoctorAvailability view(ResultSet rs) throws SQLException {
		return new DoctorAvailability(rs.getObject("doctor_id", UUID.class), rs.getString("status"), instant(rs, "since"),
				rs.getObject("shift_id", UUID.class));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, OffsetDateTime.class).toInstant();
	}

	private static OffsetDateTime ts(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
