package com.medconnect.recording_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.medconnect.recording_service.jobs.RecordingTimeoutJob;

class RecordingTimeoutIT extends AbstractRecordingIT {

	@Autowired
	RecordingTimeoutJob job;

	private void age(String table, String where, Object key) {
		jdbc.update("update " + table + " set created_at = now() - interval '31 minutes' where " + where + " = ?", key);
	}

	@Test
	void stuckProcessingBecomesFailedOnce() {
		UUID cid = mapSession("s-" + UUID.randomUUID());
		UUID rid = UUID.randomUUID();
		jdbc.update("insert into recordings(id, consultation_id, provider_recording_id, status) values (?, ?, ?, 'PROCESSING')", rid, cid, "p-" + rid);
		age("recordings", "id", rid);

		job.run();
		job.run(); // idempotent

		assertThat(jdbc.queryForObject("select status from recordings where id = ?", String.class, rid)).isEqualTo("FAILED");
		assertThat(outboxTypes(cid)).containsExactly("RecordingFailed");
		assertThat(jdbc.queryForObject("select envelope->'payload'->>'reason' from outbox where envelope->>'aggregateId' = ?", String.class,
				cid.toString())).isEqualTo("provider webhook timeout");
	}

	@Test
	void completedSessionWithoutWebhookGetsFailedRow() {
		UUID cid = mapSession("s-" + UUID.randomUUID());
		age("session_map", "consultation_id", cid);

		job.run();
		job.run();

		assertThat(jdbc.queryForObject("select count(*) from recordings where consultation_id = ? and status = 'FAILED'", Integer.class, cid)).isEqualTo(1);
		assertThat(outboxTypes(cid)).containsExactly("RecordingFailed");
	}

	@Test
	void lateCallbackAfterStuckTimeoutReopensSameRow() throws Exception {
		String sid = "s-" + UUID.randomUUID();
		String prov = "p-" + UUID.randomUUID();
		UUID cid = mapSession(sid);
		UUID rid = UUID.randomUUID();
		jdbc.update("insert into recordings(id, consultation_id, provider_recording_id, status) values (?, ?, ?, 'PROCESSING')", rid, cid, prov);
		age("recordings", "id", rid);
		job.run();
		upload(prov);

		assertThat(webhook(completed(sid, prov)).getStatus()).isEqualTo(202);

		assertThat(jdbc.queryForObject("select status from recordings where id = ?", String.class, rid)).isEqualTo("AVAILABLE");
		assertThat(outboxTypes(cid)).containsExactly("RecordingFailed", "RecordingAvailable");
	}

	@Test
	void lateCallbackWithNewProviderIdAfterSilentTimeoutReusesRow() throws Exception {
		String sid = "s-" + UUID.randomUUID();
		String prov = "p-" + UUID.randomUUID();
		UUID cid = mapSession(sid);
		age("session_map", "consultation_id", cid);
		job.run();
		UUID rid = jdbc.queryForObject("select id from recordings where consultation_id = ?", UUID.class, cid);
		upload(prov);

		assertThat(webhook(completed(sid, prov)).getStatus()).isEqualTo(202);

		assertThat(jdbc.queryForObject("select count(*) from recordings where consultation_id = ?", Integer.class, cid)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select id from recordings where consultation_id = ?", UUID.class, cid)).isEqualTo(rid);
		assertThat(jdbc.queryForObject("select status from recordings where id = ?", String.class, rid)).isEqualTo("AVAILABLE");
	}

	// S-F2-05
	@Test
	void duplicateOfAvailableRecordingStays409() throws Exception {
		String sid = "s-" + UUID.randomUUID();
		String prov = "p-" + UUID.randomUUID();
		mapSession(sid);
		upload(prov);
		assertThat(webhook(completed(sid, prov)).getStatus()).isEqualTo(202);
		assertThat(webhook(completed(sid, prov)).getStatus()).isEqualTo(409);
	}

	@Test
	void freshProcessingRowIsLeftAlone() {
		UUID cid = mapSession("s-" + UUID.randomUUID());
		UUID rid = UUID.randomUUID();
		jdbc.update("insert into recordings(id, consultation_id, provider_recording_id, status) values (?, ?, ?, 'PROCESSING')", rid, cid, "p-" + rid);
		job.run();
		assertThat(jdbc.queryForObject("select status from recordings where id = ?", String.class, rid)).isEqualTo("PROCESSING");
	}
}
