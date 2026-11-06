package com.medconnect.apigateway;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/** Minimal RFC 7807 writer for errors produced by the gateway itself (401/403/429). */
final class ProblemResponses {

	private ProblemResponses() {}

	static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String detail) {
		var response = exchange.getResponse();
		response.setStatusCode(status);
		response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		String instance = exchange.getRequest().getPath().value().replaceAll("[^A-Za-z0-9/._~-]", "");
		String body = "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"instance\":\"%s\"}"
				.formatted(status.getReasonPhrase(), status.value(), detail, instance);
		DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
		return response.writeWith(Mono.just(buffer));
	}
}
