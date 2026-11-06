package com.medconnect.apigateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/** Redis token bucket per key (D11). Own filter instead of the RequestRateLimiter route filter to emit Retry-After + problem+json. */
@Component
class RateLimitGlobalFilter implements GlobalFilter {

	private static final String ROUTE_KEY = "gateway";

	private final RedisRateLimiter limiter;
	private final RateLimitKeyResolver keys;
	private final long retryAfterSeconds;

	RateLimitGlobalFilter(RedisRateLimiter limiter, RateLimitKeyResolver keys,
			@Value("${gateway.rate-limit.replenish-rate}") int replenishRate,
			@Value("${gateway.rate-limit.burst-capacity}") int burstCapacity) {
		this.limiter = limiter;
		this.keys = keys;
		this.retryAfterSeconds = Math.max(1, (long) Math.ceil(1.0 / replenishRate));
		limiter.getConfig().put(ROUTE_KEY,
				new RedisRateLimiter.Config().setReplenishRate(replenishRate).setBurstCapacity(burstCapacity));
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		return keys.resolve(exchange)
				.flatMap(key -> limiter.isAllowed(ROUTE_KEY, key))
				.flatMap(r -> {
					if (r.isAllowed()) {
						return chain.filter(exchange);
					}
					exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));
					return ProblemResponses.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
				});
	}
}
