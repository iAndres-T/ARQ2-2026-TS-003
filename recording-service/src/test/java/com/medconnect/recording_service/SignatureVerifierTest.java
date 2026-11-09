package com.medconnect.recording_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.medconnect.recording_service.webhook.SignatureVerifier;

class SignatureVerifierTest {

	static final String SECRET = "s3cret";

	@Test
	void signatureUsesRawBytes() {
		byte[] raw = "{\"type\":\"recording.completed\"}".getBytes(StandardCharsets.UTF_8);
		String sig = SignatureVerifier.hmacHex(raw, SECRET);
		assertThat(SignatureVerifier.verify(raw, sig, SECRET)).isTrue();
		// same JSON, re-pretty-printed: different bytes, so it must fail
		byte[] pretty = "{ \"type\": \"recording.completed\" }".getBytes(StandardCharsets.UTF_8);
		assertThat(SignatureVerifier.verify(pretty, sig, SECRET)).isFalse();
	}

	@Test
	void rejectsMissingOrWrongSignature() {
		byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);
		assertThat(SignatureVerifier.verify(raw, null, SECRET)).isFalse();
		assertThat(SignatureVerifier.verify(raw, "abcd", SECRET)).isFalse();
		assertThat(SignatureVerifier.verify(raw, SignatureVerifier.hmacHex(raw, "other"), SECRET)).isFalse();
	}
}
