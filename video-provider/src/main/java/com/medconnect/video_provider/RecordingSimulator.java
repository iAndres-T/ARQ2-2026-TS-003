package com.medconnect.video_provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

/**
 * Mock of the provider's recording pipeline (D9): some time after a session ends it uploads ~100 KB to
 * {@code incoming/{providerRecordingId}.mp4} and calls the signed webhook. The webhook is retried with backoff on
 * 5xx (recording-service answers 503 until it has seen ConsultationCompleted). Mode {@code failed} sends
 * {@code recording.failed} instead, for demos.
 */
@Component
class RecordingSimulator {

	private static final Logger log = LoggerFactory.getLogger(RecordingSimulator.class);
	static final int SIZE_BYTES = 100 * 1024;

	private final ObjectUploader uploader;
	private final HttpClient http;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "recording-simulator");
		t.setDaemon(true);
		return t;
	});
	private final AtomicReference<String> mode;
	private final String webhookUrl;
	private final String secret;
	private final Duration delay;
	private final Duration retryInitial;
	private final int maxAttempts;

	RecordingSimulator(ObjectUploader uploader, SslBundles sslBundles,
			@Value("${video-provider.webhook-ssl-bundle:}") String sslBundle, @Value("${video-provider.webhook-url}") String webhookUrl,
			@Value("${video-provider.webhook-secret}") String secret, @Value("${video-provider.recording.mode:completed}") String mode,
			@Value("${video-provider.recording.delay:5s}") Duration delay,
			@Value("${video-provider.recording.retry-initial:2s}") Duration retryInitial,
			@Value("${video-provider.recording.max-attempts:12}") int maxAttempts) {
		this.uploader = uploader;
		// the gateway's dev certificate is trusted via a Boot SSL bundle (no trust-all); empty bundle = JVM defaults
		HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
		if (!sslBundle.isBlank()) {
			client.sslContext(sslBundles.getBundle(sslBundle).createSslContext());
		}
		this.http = client.build();
		this.webhookUrl = webhookUrl;
		this.secret = secret;
		this.mode = new AtomicReference<>(mode);
		this.delay = delay;
		this.retryInitial = retryInitial;
		this.maxAttempts = maxAttempts;
	}

	void setMode(String newMode) {
		mode.set(newMode);
	}

	void onSessionEnded(String sessionId) {
		scheduler.schedule(() -> produce(sessionId), delay.toMillis(), TimeUnit.MILLISECONDS);
	}

	private void produce(String sessionId) {
		String recordingId = "rec_" + UUID.randomUUID();
		String body;
		boolean failed = mode.get().equals("failed");
		if (!failed) {
			try {
				String key = "incoming/" + recordingId + ".mp4";
				uploader.upload(key, new byte[SIZE_BYTES]);
				body = "{\"type\":\"recording.completed\",\"sessionId\":\"%s\",\"providerRecordingId\":\"%s\",\"durationSeconds\":120,\"downloadUrl\":\"https://video.provider.example/recordings/%s\",\"objectKey\":\"%s\"}"
					.formatted(sessionId, recordingId, recordingId, key);
			}
			catch (RuntimeException ex) {
				log.warn("Upload of {} failed, reporting recording.failed: {}", recordingId, ex.toString());
				failed = true;
				body = null;
			}
		}
		else {
			body = null;
		}
		if (failed) {
			body = "{\"type\":\"recording.failed\",\"sessionId\":\"%s\",\"providerRecordingId\":\"%s\",\"reason\":\"Simulated provider failure\"}"
				.formatted(sessionId, recordingId);
		}
		send(body.getBytes(StandardCharsets.UTF_8), recordingId, 1);
	}

	private void send(byte[] body, String recordingId, int attempt) {
		int status = -1;
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.header("X-Provider-Signature", sign(body))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
			status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		}
		catch (IOException ex) {
			log.warn("Webhook for {} not delivered: {}", recordingId, ex.toString());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return;
		}
		if (status == 202 || status == 409) {
			log.info("Webhook for {} delivered (status {})", recordingId, status);
		}
		else if ((status == -1 || status >= 500) && attempt < maxAttempts) {
			long wait = Math.min(retryInitial.toMillis() << Math.min(attempt - 1, 5), 30_000);
			scheduler.schedule(() -> send(body, recordingId, attempt + 1), wait, TimeUnit.MILLISECONDS);
		}
		else {
			log.error("Webhook for {} abandoned (status {}, attempt {})", recordingId, status, attempt);
		}
	}

	private String sign(byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(body));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
