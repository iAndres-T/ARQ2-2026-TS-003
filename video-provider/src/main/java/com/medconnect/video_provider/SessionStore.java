package com.medconnect.video_provider;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** In-memory rooms of the mock provider; one room per consultation (idempotent create). Lost on restart. */
@Component
class SessionStore {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final Map<UUID, Session> byConsultation = new ConcurrentHashMap<>();
	private final Map<String, Session> bySession = new ConcurrentHashMap<>();
	private final String publicUrl;

	SessionStore(@Value("${video-provider.public-url:http://localhost:8090}") String publicUrl) {
		this.publicUrl = publicUrl;
	}

	Session create(UUID consultationId) {
		return byConsultation.computeIfAbsent(consultationId, cid -> {
			String sid = UUID.randomUUID().toString();
			Session s = new Session(sid, cid, joinUrl(sid), joinUrl(sid));
			bySession.put(sid, s);
			return s;
		});
	}

	/** Returns the ended session, or null when unknown. */
	Session end(String sessionId) {
		return bySession.get(sessionId);
	}

	private String joinUrl(String sessionId) {
		byte[] token = new byte[16];
		RANDOM.nextBytes(token);
		return publicUrl + "/room/" + sessionId + "?t=" + HexFormat.of().formatHex(token);
	}

	record Session(String sessionId, UUID consultationId, String patientJoinUrl, String doctorJoinUrl) {
	}
}
