package com.medconnect.consultation_service.video;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * Anti-Corruption Layer for the external video provider (spec §3 "Integration contract: Video provider"): every call
 * has a 3 s timeout and session creation is retried twice. The provider's payloads are translated here into
 * {@link VideoSession}.
 */
@Component
public class VideoProviderClient {

	private static final Logger log = LoggerFactory.getLogger(VideoProviderClient.class);

	private final RestClient http;
	private final Retry retry;

	VideoProviderClient(@Value("${medconnect.video.url}") String baseUrl, @Value("${medconnect.video.timeout:3s}") Duration timeout,
			@Value("${medconnect.video.retries:2}") int retries) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(timeout).build());
		factory.setReadTimeout(timeout);
		this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
		this.retry = Retry.of("video-provider",
				RetryConfig.custom().maxAttempts(retries + 1).waitDuration(Duration.ofMillis(200)).build());
	}

	/** Idempotent per consultation on the provider side, so a redelivered PatientAssigned never opens a second room. */
	public VideoSession createSession(UUID consultationId) {
		ProviderSession s = retry.executeSupplier(() -> http.post().uri("/sessions")
			.body(Map.of("consultationId", consultationId))
			.retrieve()
			.body(ProviderSession.class));
		if (s == null || s.sessionId() == null || s.joinUrls() == null) {
			throw new IllegalStateException("Video provider returned an incomplete session");
		}
		return new VideoSession(s.sessionId(), s.joinUrls().patient(), s.joinUrls().doctor());
	}

	/** Best effort: one attempt, failures are only logged (the provider closes idle rooms itself). */
	public void endSession(String sessionId) {
		try {
			http.post().uri("/sessions/{id}/end", sessionId).retrieve().toBodilessEntity();
		}
		catch (RuntimeException ex) {
			log.warn("Ending video session {} failed: {}", sessionId, ex.toString());
		}
	}

	/** Provider wire model; never leaves this class. */
	record ProviderSession(String sessionId, JoinUrls joinUrls) {
		record JoinUrls(String patient, String doctor) {
		}
	}
}
