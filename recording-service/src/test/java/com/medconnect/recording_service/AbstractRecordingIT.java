package com.medconnect.recording_service;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.medconnect.recording_service.webhook.SignatureVerifier;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestContainers.class)
abstract class AbstractRecordingIT {

	static final String SECRET = "dev-video-webhook-secret";
	static final String BUCKET = "medconnect-recordings";
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	WebApplicationContext ctx;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	S3Client s3;
	MockMvc mvc;

	@BeforeEach
	void baseSetUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
		try {
			s3.createBucket(b -> b.bucket(BUCKET));
		}
		catch (BucketAlreadyOwnedByYouException ex) {
			// already there
		}
	}

	/** Maps a session to a fresh consultation, as ConsultationCompleted would. */
	static final UUID DOCTOR = UUID.randomUUID();

	UUID mapSession(String sessionId) {
		UUID cid = UUID.randomUUID();
		jdbc.update("insert into session_map(session_id, consultation_id, doctor_id, patient_id) values (?, ?, ?, ?)", sessionId, cid,
				DOCTOR, UUID.randomUUID());
		return cid;
	}

	/** Uploads a 2 KB object to incoming/{providerRecordingId}.mp4 with a SHA-256 checksum, like the video provider. */
	void upload(String providerRecordingId) {
		s3.putObject(p -> p.bucket(BUCKET).key("incoming/" + providerRecordingId + ".mp4").checksumAlgorithm(ChecksumAlgorithm.SHA256),
				RequestBody.fromBytes(new byte[2048]));
	}

	static String completed(String sessionId, String providerRecordingId) {
		return "{\"type\":\"recording.completed\",\"sessionId\":\"%s\",\"providerRecordingId\":\"%s\",\"durationSeconds\":2230,\"objectKey\":\"incoming/%s.mp4\"}"
			.formatted(sessionId, providerRecordingId, providerRecordingId);
	}

	MockHttpServletResponse webhook(String body) throws Exception {
		byte[] raw = body.getBytes(StandardCharsets.UTF_8);
		return webhook(raw, SignatureVerifier.hmacHex(raw, SECRET));
	}

	MockHttpServletResponse webhook(byte[] body, String signature) throws Exception {
		var req = post("/api/v1/webhooks/video-provider").contentType(MediaType.APPLICATION_JSON).content(body);
		if (signature != null) {
			req.header("X-Provider-Signature", signature);
		}
		return mvc.perform(req).andReturn().getResponse();
	}

	List<String> outboxTypes(UUID consultationId) {
		return jdbc.queryForList("select envelope->>'eventType' from outbox where envelope->>'aggregateId' = ? order by created_at",
				String.class, consultationId.toString());
	}
}
