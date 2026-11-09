package com.medconnect.recording_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.JsonNode;

class RecordingApiIT extends AbstractRecordingIT {

	static RequestPostProcessor staff(boolean withScope) {
		return staff(withScope, DOCTOR);
	}

	static RequestPostProcessor staff(boolean withScope, UUID doctorId) {
		var jwt = jwt().jwt(j -> {
			j.subject(UUID.randomUUID().toString()).claim("role", "MEDICAL_STAFF").claim("doctorId", doctorId.toString());
			if (withScope) {
				j.claim("scopes", List.of("recordings:read"));
			}
		});
		return withScope
				? jwt.authorities(new SimpleGrantedAuthority("ROLE_MEDICAL_STAFF"), new SimpleGrantedAuthority("SCOPE_recordings:read"))
				: jwt.authorities(new SimpleGrantedAuthority("ROLE_MEDICAL_STAFF"));
	}

	static RequestPostProcessor patient() {
		return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "PATIENT"))
			.authorities(new SimpleGrantedAuthority("ROLE_PATIENT"));
	}

	MockHttpServletResponse getRecording(UUID cid, RequestPostProcessor user) throws Exception {
		return mvc.perform(get("/api/v1/consultations/" + cid + "/recording").with(user)).andReturn().getResponse();
	}

	// S-F2-01
	@Test
	void availableReturnsPresignedUrl300s() throws Exception {
		String sid = "s-" + UUID.randomUUID();
		String rec = "rec-" + UUID.randomUUID();
		UUID cid = mapSession(sid);
		upload(rec);
		assertThat(webhook(completed(sid, rec)).getStatus()).isEqualTo(202);

		MockHttpServletResponse r = getRecording(cid, staff(true));
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode body = JSON.readTree(r.getContentAsString());
		assertThat(body.get("status").asString()).isEqualTo("AVAILABLE");
		assertThat(body.get("url").asString()).startsWith("s3://medconnect-recordings/recordings/");
		assertThat(body.get("sizeBytes").asLong()).isEqualTo(2048L);
		String download = body.get("downloadUrl").asString();
		assertThat(download).contains("X-Amz-Expires=300");
		// the pre-signed URL really serves the object, bypassing the service
		HttpResponse<byte[]> media = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(download)).build(), HttpResponse.BodyHandlers.ofByteArray());
		assertThat(media.statusCode()).isEqualTo(200);
		assertThat(media.body()).hasSize(2048);
	}

	// S-F2-03
	@Test
	void patientOrNoScopeIs403() throws Exception {
		UUID cid = UUID.randomUUID();
		assertThat(getRecording(cid, patient()).getStatus()).isEqualTo(403);
		assertThat(getRecording(cid, staff(false)).getStatus()).isEqualTo(403);
		assertThat(mvc.perform(get("/api/v1/consultations/" + cid + "/recording")).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	// S-F2-03 + ADR-003 resource rule
	@Test
	void otherDoctorWithScopeIs403() throws Exception {
		UUID cid = mapSession("s-" + UUID.randomUUID());
		assertThat(getRecording(cid, staff(true, UUID.randomUUID())).getStatus()).isEqualTo(403);
		assertThat(getRecording(cid, staff(true)).getStatus()).isEqualTo(404); // assigned doctor, no recording yet
	}

	@Test
	void legacyRowWithoutDoctorDeniesAccess() throws Exception {
		UUID cid = UUID.randomUUID();
		jdbc.update("insert into session_map(session_id, consultation_id) values (?, ?)", "s-" + UUID.randomUUID(), cid);
		assertThat(getRecording(cid, staff(true)).getStatus()).isEqualTo(403);
	}

	@Test
	void malformedOrMissingDoctorClaimIs403() throws Exception {
		UUID cid = mapSession("s-" + UUID.randomUUID());
		var bad = jwt().jwt(j -> j.subject("x").claim("doctorId", "not-a-uuid"))
			.authorities(new SimpleGrantedAuthority("ROLE_MEDICAL_STAFF"), new SimpleGrantedAuthority("SCOPE_recordings:read"));
		var none = jwt().jwt(j -> j.subject("x"))
			.authorities(new SimpleGrantedAuthority("ROLE_MEDICAL_STAFF"), new SimpleGrantedAuthority("SCOPE_recordings:read"));
		assertThat(getRecording(cid, bad).getStatus()).isEqualTo(403);
		assertThat(getRecording(cid, none).getStatus()).isEqualTo(403);
	}

	@Test
	void unknownConsultationIs404() throws Exception {
		assertThat(getRecording(UUID.randomUUID(), staff(true)).getStatus()).isEqualTo(404);
	}

	@Test
	void failedRecordingHasNoDownloadUrl() throws Exception {
		String sid = "s-" + UUID.randomUUID();
		UUID cid = mapSession(sid);
		webhook("{\"type\":\"recording.failed\",\"sessionId\":\"%s\",\"providerRecordingId\":\"r-%s\"}".formatted(sid, sid));
		JsonNode body = JSON.readTree(getRecording(cid, staff(true)).getContentAsString());
		assertThat(body.get("status").asString()).isEqualTo("FAILED");
		assertThat(body.get("downloadUrl").isNull()).isTrue();
	}
}
