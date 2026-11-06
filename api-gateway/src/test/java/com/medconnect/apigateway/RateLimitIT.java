package com.medconnect.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "gateway.rate-limit.replenish-rate=1", "gateway.rate-limit.burst-capacity=1" })
@AutoConfigureWebTestClient
class RateLimitIT {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		GatewayTestSupport.properties(r);
	}

	@Autowired WebTestClient client;

	@Test
	void exceedingBurstReturns429WithRetryAfter() {
		String t = GatewayTestSupport.token("PATIENT");
		for (int i = 0; i < 5; i++) {
			client.get().uri("/api/v1/consultations/1").headers(h -> h.setBearerAuth(t)).exchange();
		}
		client.get().uri("/api/v1/consultations/1").headers(h -> h.setBearerAuth(t)).exchange().expectStatus()
				.isEqualTo(429).expectHeader().exists("Retry-After").expectHeader()
				.contentTypeCompatibleWith("application/problem+json");
	}
}
