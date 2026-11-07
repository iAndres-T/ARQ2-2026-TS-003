package com.medconnect.video_provider;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Mock external video provider: session API used by consultation-service's ACL. No security (internal network only). */
@RestController
class SessionController {

	private static final Logger log = LoggerFactory.getLogger(SessionController.class);

	private final SessionStore store;
	private final RecordingSimulator recordings;

	SessionController(SessionStore store, RecordingSimulator recordings) {
		this.store = store;
		this.recordings = recordings;
	}

	@PostMapping("/sessions")
	Map<String, Object> create(@Valid @RequestBody CreateSession body) {
		SessionStore.Session s = store.create(body.consultationId());
		return Map.of("sessionId", s.sessionId(), "joinUrls", Map.of("patient", s.patientJoinUrl(), "doctor", s.doctorJoinUrl()));
	}

	@PostMapping("/sessions/{sessionId}/end")
	ResponseEntity<Void> end(@PathVariable String sessionId) {
		SessionStore.Session s = store.end(sessionId);
		if (s == null) {
			return ResponseEntity.notFound().build();
		}
		log.info("Session {} ended for consultation {}", s.sessionId(), s.consultationId());
		recordings.onSessionEnded(s.sessionId());
		return ResponseEntity.noContent().build();
	}

	/** Demo toggle: {@code failed} makes the next recordings report {@code recording.failed}; {@code completed} restores the default. */
	@PutMapping("/admin/recording-mode/{mode}")
	ResponseEntity<Void> recordingMode(@PathVariable String mode) {
		if (!mode.equals("completed") && !mode.equals("failed")) {
			return ResponseEntity.badRequest().build();
		}
		recordings.setMode(mode);
		return ResponseEntity.noContent().build();
	}

	record CreateSession(@NotNull UUID consultationId) {
	}
}
