package com.medconnect.apigateway;

import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Accepts an incoming X-Correlation-Id only if it is a UUID, otherwise generates one; forwards it to the backend
 * and echoes it in the response. A WebFilter (not a route filter) so 401/403/429 answers carry it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdGlobalFilter implements WebFilter {

	static final String HEADER = "X-Correlation-Id";

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String id = exchange.getRequest().getHeaders().getFirst(HEADER);
		if (!isUuid(id)) {
			id = UUID.randomUUID().toString();
		}
		String correlationId = id;
		exchange.getResponse().beforeCommit(() -> {
			exchange.getResponse().getHeaders().set(HEADER, correlationId);
			return Mono.empty();
		});
		return chain.filter(exchange.mutate().request(r -> r.headers(h -> h.set(HEADER, correlationId))).build());
	}

	private static boolean isUuid(String value) {
		try {
			return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}
}
