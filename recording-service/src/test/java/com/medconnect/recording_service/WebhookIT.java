package com.medconnect.recording_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WebhookIT extends AbstractRecordingIT {

	@Test
	void badSignatureIs401() throws Exception {
		String sid = "s-" + UUID.randomUUID();
		mapSession(sid);
		assertThat(webhook(completed(sid, "rec-" + sid).getBytes(), "00ff").getStatus()).isEqualTo(401);
		assertThat(webhook("{}".getBytes(), null).getStatus()).isEqualTo(401);
	}

	@Test
	void malformedPayloadIs400() throws Exception {
		assertThat(webhook("not json").getStatus()).isEqualTo(400);
		assertThat(webhook("{\"type\":\"recording.completed\"}").getStatus()).isEqualTo(400);
	}

	@Test
	void unmappedSessionIs503() throws Exception {
		assertThat(webhook(completed("s-unknown-" + UUID.randomUUID(), "rec-x-" + UUID.randomUUID())).getStatus()).isEqualTo(503);
	}

	// S-F2-02
	@Test
	void completedWebhookStoresMetadataOnly() throws Exception {
		String sid = "s-" + UUID.randomUUID();
		String rec = "rec-" + UUID.randomUUID();
		UUID cid = mapSession(sid);
		upload(rec);

		assertThat(webhook(completed(sid, rec)).getStatus()).isEqualTo(202);

		Map<String, Object> row = jdbc.queryForMap("select * from recordings where provider_recording_id = ?", rec);
		UUID recordingId = (UUID) row.get("id");
		assertThat(row.get("status")).isEqualTo("AVAILABLE");
		assertThat((String) row.get("url")).matches("s3://medconnect-recordings/recordings/\\d{4}/\\d{2}/" + cid + "/" + recordingId + "\\.mp4");
		assertThat((Long) row.get("size_bytes")).isEqualTo(2048L);
		assertThat((String) row.get("checksum_sha256")).matches("[0-9a-f]{64}");
		assertThat(row.get("duration_seconds")).isEqualTo(2230);
		assertThat(outboxTypes(cid)).containsExactly("RecordingAvailable");
	}

	// S-F2-05
	@Test
	void duplicateWebhookIs409NoSecondRow() throws Exception {
		String sid = "s-" + UUID.randomUUID();
		String rec = "rec-" + UUID.randomUUID();
		UUID cid = mapSession(sid);
		upload(rec);
		assertThat(webhook(completed(sid, rec)).getStatus()).isEqualTo(202);
		assertThat(webhook(completed(sid, rec)).getStatus()).isEqualTo(409);
		assertThat(jdbc.queryForObject("select count(*) from recordings where provider_recording_id = ?", Integer.class, rec)).isEqualTo(1);
		assertThat(outboxTypes(cid)).hasSize(1);
	}

	// S-F2-04
	@Test
	void failedWebhookEmitsRecordingFailed() throws Exception {
		String sid = "s-" + UUID.randomUUID();
		String rec = "rec-" + UUID.randomUUID();
		UUID cid = mapSession(sid);
		String body = "{\"type\":\"recording.failed\",\"sessionId\":\"%s\",\"providerRecordingId\":\"%s\",\"reason\":\"encoder crashed\"}".formatted(sid, rec);
		assertThat(webhook(body).getStatus()).isEqualTo(202);
		assertThat(jdbc.queryForObject("select status from recordings where provider_recording_id = ?", String.class, rec)).isEqualTo("FAILED");
		assertThat(outboxTypes(cid)).containsExactly("RecordingFailed");
		assertThat(webhook(body).getStatus()).isEqualTo(409);
	}

	// S-F2-04: the object never arrives; 503 twice (the provider retries), then FAILED on the third attempt
	@Test
	void promoteFailingThreeTimesEndsInRecordingFailed() throws Exception {
		String sid = "s-" + UUID.randomUUID();
		String rec = "rec-" + UUID.randomUUID();
		UUID cid = mapSession(sid);
		String body = completed(sid, rec);
		assertThat(webhook(body).getStatus()).isEqualTo(503);
		assertThat(webhook(body).getStatus()).isEqualTo(503);
		assertThat(outboxTypes(cid)).isEmpty();
		assertThat(webhook(body).getStatus()).isEqualTo(202);
		assertThat(jdbc.queryForObject("select status from recordings where provider_recording_id = ?", String.class, rec)).isEqualTo("FAILED");
		assertThat(outboxTypes(cid)).containsExactly("RecordingFailed");
		assertThat(webhook(body).getStatus()).isEqualTo(409);
	}
}
