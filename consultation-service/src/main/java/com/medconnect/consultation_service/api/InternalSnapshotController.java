package com.medconnect.consultation_service.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.common.web.ApiException;
import com.medconnect.consultation_service.app.ConsultationService;

/**
 * Spec §4: snapshot of WAITING consultations so care-queue-service can rebuild Redis without reading our tables.
 * The gateway never routes {@code /internal/**}; inside the network callers must send the shared
 * {@code X-Internal-Token} (env {@code INTERNAL_TOKEN}).
 */
@RestController
class InternalSnapshotController {

	static final String TOKEN_HEADER = "X-Internal-Token";

	private final ConsultationService service;
	private final byte[] token;

	InternalSnapshotController(ConsultationService service, @Value("${medconnect.internal-token}") String token) {
		this.service = service;
		this.token = token.getBytes(StandardCharsets.UTF_8);
	}

	@GetMapping("/internal/v1/consultations/waiting")
	List<Map<String, Object>> waiting(@RequestHeader(value = TOKEN_HEADER, required = false) String given) {
		if (given == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Missing or invalid internal token");
		}
		return service.waitingSnapshot();
	}
}
