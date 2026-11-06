package com.medconnect.apigateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/** Key = JWT sub; client IP on public routes (no JWT). */
@Component
class RateLimitKeyResolver implements KeyResolver {

	@Override
	public Mono<String> resolve(ServerWebExchange exchange) {
		return exchange.getPrincipal()
				.filter(JwtAuthenticationToken.class::isInstance)
				.map(p -> "sub:" + p.getName())
				.switchIfEmpty(Mono.fromSupplier(() -> {
					var addr = exchange.getRequest().getRemoteAddress();
					return "ip:" + (addr == null ? "unknown" : addr.getAddress().getHostAddress());
				}));
	}
}
