package com.medconnect.recording_service.webhook;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.medconnect.common.web.ApiException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
class WebhookController {

	private final WebhookService service;
	private final JsonMapper json;
	private final String secret;

	WebhookController(WebhookService service, JsonMapper json, @Value("${medconnect.recording.webhook-secret}") String secret) {
		this.service = service;
		this.json = json;
		this.secret = secret;
	}

	/** The body is read as raw bytes: the signature covers exactly what the provider sent. */
	@PostMapping("/api/v1/webhooks/video-provider")
	ResponseEntity<Map<String, String>> receive(@RequestBody byte[] rawBody,
			@RequestHeader(name = "X-Provider-Signature", required = false) String signature) {
		if (!SignatureVerifier.verify(rawBody, signature, secret)) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid signature", "X-Provider-Signature does not match the body");
		}
		JsonNode body;
		try {
			body = json.readTree(rawBody);
		}
		catch (JacksonException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Malformed payload", "Body is not valid JSON");
		}
		if (body == null || !body.isObject()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Malformed payload", "Body must be a JSON object");
		}
		service.handle(body);
		return ResponseEntity.accepted().body(Map.of("status", "ACCEPTED", "providerRecordingId", body.get("providerRecordingId").asString()));
	}
}
