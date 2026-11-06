package com.medconnect.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayRoutingIT {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		GatewayTestSupport.properties(r);
	}

	@Autowired WebTestClient client;

	@BeforeEach
	void reset() {
		GatewayTestSupport.DEFAULT.requests.clear();
		GatewayTestSupport.RECORDING.requests.clear();
	}

	@Test // S-F1-03
	void noTokenOnConsultationsIs401() {
		client.post().uri("/api/v1/consultations").exchange().expectStatus().isUnauthorized()
				.expectHeader().contentTypeCompatibleWith("application/problem+json")
				.expectBody().jsonPath("$.status").isEqualTo(401);
		assertThat(GatewayTestSupport.DEFAULT.requests).isEmpty();
	}

	@Test // S-F1-03
	void expiredTokenIs401() {
		String t = GatewayTestSupport.token("PATIENT", "medconnect-api", Instant.now().minusSeconds(120));
		client.post().uri("/api/v1/consultations").headers(h -> h.setBearerAuth(t)).exchange().expectStatus().isUnauthorized();
	}

	@Test // S-F1-03
	void wrongAudienceIs401() {
		String t = GatewayTestSupport.token("PATIENT", "other-api", Instant.now().plusSeconds(300));
		client.post().uri("/api/v1/consultations").headers(h -> h.setBearerAuth(t)).exchange().expectStatus().isUnauthorized();
	}

	@Test // S-F1-04
	void patientOnQueueClaimsIs403() {
		client.post().uri("/api/v1/queue/claims").headers(h -> h.setBearerAuth(GatewayTestSupport.token("PATIENT")))
				.exchange().expectStatus().isForbidden().expectHeader().contentTypeCompatibleWith("application/problem+json");
	}

	@Test // S-F1-04
	void patientOnOverrideIs403() {
		client.post().uri("/api/v1/triage/consultations/abc/override")
				.headers(h -> h.setBearerAuth(GatewayTestSupport.token("PATIENT"))).exchange().expectStatus().isForbidden();
	}

	@Test // S-F1-05
	void staffPostingConsultationIs403() {
		client.post().uri("/api/v1/consultations")
				.headers(h -> h.setBearerAuth(GatewayTestSupport.token("MEDICAL_STAFF"))).exchange().expectStatus().isForbidden();
	}

	@Test
	void patientPostingConsultationIsRoutedUnchanged() {
		client.post().uri("/api/v1/consultations").headers(h -> h.setBearerAuth(GatewayTestSupport.token("PATIENT")))
				.exchange().expectStatus().isOk();
		assertThat(GatewayTestSupport.DEFAULT.requests).singleElement()
				.satisfies(r -> assertThat(r.path()).isEqualTo("/api/v1/consultations"));
	}

	@Test
	void publicAuthRouteNeedsNoToken() {
		client.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
		client.get().uri("/api/v1/.well-known/jwks.json").exchange().expectStatus().isOk();
	}

	@Test
	void correlationIdGeneratedAndForwarded() {
		var res = client.get().uri("/api/v1/consultations/1").headers(h -> h.setBearerAuth(GatewayTestSupport.token("PATIENT")))
				.exchange().expectStatus().isOk().returnResult(String.class);
		String echoed = res.getResponseHeaders().getFirst("X-Correlation-Id");
		assertThat(UUID.fromString(echoed)).isNotNull();
		assertThat(GatewayTestSupport.DEFAULT.requests.get(0).headers().get("x-correlation-id")).isEqualTo(echoed);
	}

	@Test
	void validIncomingCorrelationIdKeptInvalidReplaced() {
		String id = UUID.randomUUID().toString();
		client.get().uri("/api/v1/consultations/1").headers(h -> {
			h.setBearerAuth(GatewayTestSupport.token("PATIENT"));
			h.set("X-Correlation-Id", id);
		}).exchange().expectHeader().valueEquals("X-Correlation-Id", id);
		var res = client.get().uri("/api/v1/consultations/1").headers(h -> {
			h.setBearerAuth(GatewayTestSupport.token("PATIENT"));
			h.set("X-Correlation-Id", "not-a-uuid");
		}).exchange().returnResult(String.class);
		assertThat(UUID.fromString(res.getResponseHeaders().getFirst("X-Correlation-Id"))).isNotNull();
	}

	@Test
	void recordingRouteWinsOverConsultations() {
		client.get().uri("/api/v1/consultations/abc/recording")
				.headers(h -> h.setBearerAuth(GatewayTestSupport.token("MEDICAL_STAFF"))).exchange().expectStatus().isOk();
		assertThat(GatewayTestSupport.RECORDING.requests).hasSize(1);
		assertThat(GatewayTestSupport.DEFAULT.requests).isEmpty();
	}

	@Test
	void patientOnRecordingIs403() {
		client.get().uri("/api/v1/consultations/abc/recording")
				.headers(h -> h.setBearerAuth(GatewayTestSupport.token("PATIENT"))).exchange().expectStatus().isForbidden();
	}
}
