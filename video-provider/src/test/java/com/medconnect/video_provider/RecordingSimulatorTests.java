package com.medconnect.video_provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = { "video-provider.recording.delay=0s", "video-provider.recording.retry-initial=50ms",
		"video-provider.webhook-secret=test-secret" })
class RecordingSimulatorTests {

	record Delivery(byte[] body, String signature) {
	}

	static final BlockingQueue<Delivery> DELIVERIES = new LinkedBlockingQueue<>();
	static final AtomicInteger CALLS = new AtomicInteger();
	static final HttpServer WEBHOOK;

	static {
		try {
			WEBHOOK = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		WEBHOOK.createContext("/hook", ex -> {
			DELIVERIES.add(new Delivery(ex.getRequestBody().readAllBytes(), ex.getRequestHeaders().getFirst("X-Provider-Signature")));
			// first delivery: 503 like recording-service before ConsultationCompleted is consumed
			ex.sendResponseHeaders(CALLS.getAndIncrement() == 0 ? 503 : 202, -1);
			ex.close();
		});
		WEBHOOK.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("video-provider.webhook-url", () -> "http://127.0.0.1:" + WEBHOOK.getAddress().getPort() + "/hook");
	}

	@Autowired
	WebApplicationContext ctx;
	@MockitoBean
	ObjectUploader uploader;

	static String hmac(byte[] body) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(body));
	}

	@Test
	void sessionEndUploadsThenSendsSignedWebhookAndRetriesOn503() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
		var created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"consultationId\":\"" + UUID.randomUUID() + "\"}")).andReturn().getResponse();
		String sid = JsonMapper.builder().build().readTree(created.getContentAsString()).get("sessionId").asString();
		assertThat(mvc.perform(post("/sessions/" + sid + "/end")).andReturn().getResponse().getStatus()).isEqualTo(204);

		Delivery first = DELIVERIES.poll(10, TimeUnit.SECONDS);
		Delivery retry = DELIVERIES.poll(10, TimeUnit.SECONDS);
		assertThat(first).isNotNull();
		assertThat(retry).isNotNull();
		assertThat(retry.body()).isEqualTo(first.body()); // identical bytes, identical signature
		assertThat(retry.signature()).isEqualTo(hmac(retry.body())).isEqualTo(first.signature());
		JsonNode json = JsonMapper.builder().build().readTree(retry.body());
		assertThat(json.get("type").asString()).isEqualTo("recording.completed");
		assertThat(json.get("sessionId").asString()).isEqualTo(sid);
		String key = json.get("objectKey").asString();
		assertThat(key).isEqualTo("incoming/" + json.get("providerRecordingId").asString() + ".mp4");
		ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
		verify(uploader, timeout(5000)).upload(eq(key), bytes.capture());
		assertThat(bytes.getValue()).hasSize(RecordingSimulator.SIZE_BYTES);
		assertThat(List.of(json.get("durationSeconds").asInt())).allMatch(d -> d > 0);
	}
}
