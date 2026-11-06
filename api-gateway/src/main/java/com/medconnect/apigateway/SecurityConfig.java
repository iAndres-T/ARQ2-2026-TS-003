package com.medconnect.apigateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;

import io.micrometer.observation.ObservationPredicate;
import reactor.core.publisher.Flux;

/** JWT (RS256, JWKS) validation and the coarse role table (ADR-005). Fine-grained rules stay in the services. */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

	/** Health checks and Prometheus scrapes would otherwise flood Jaeger with one trace each. */
	@Bean
	ObservationPredicate skipActuatorObservations() {
		return (name, context) -> !(context instanceof ServerRequestObservationContext server
				&& server.getCarrier().getURI().getPath().startsWith("/actuator"));
	}

	private static final String STAFF = "MEDICAL_STAFF";

	@Bean
	SecurityWebFilterChain security(ServerHttpSecurity http) {
		// PathPattern has no mid-path "**", so /triage/**/override is matched by hand
		ServerWebExchangeMatcher triageOverride = exchange -> {
			String p = exchange.getRequest().getPath().value();
			return p.startsWith("/api/v1/triage/") && p.endsWith("/override") ? MatchResult.match() : MatchResult.notMatch();
		};
		return http
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.authorizeExchange(a -> a
						.pathMatchers("/api/v1/auth/**", "/api/v1/.well-known/jwks.json", "/api/v1/webhooks/video-provider",
								"/actuator/health/**", "/actuator/prometheus").permitAll()
						.matchers(triageOverride).hasRole(STAFF)
						.pathMatchers("/api/v1/queue/**", "/api/v1/doctors/**", "/api/v1/shifts/**",
								"/api/v1/consultations/*/recording", "/api/v1/audit/**").hasRole(STAFF)
						.pathMatchers(HttpMethod.POST, "/api/v1/prescriptions").hasRole(STAFF)
						.pathMatchers(HttpMethod.POST, "/api/v1/consultations").hasRole("PATIENT")
						.anyExchange().authenticated())
				.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter())))
				.exceptionHandling(e -> e
						.authenticationEntryPoint((ex, err) -> ProblemResponses.write(ex, HttpStatus.UNAUTHORIZED,
								"Missing or invalid access token"))
						.accessDeniedHandler((ex, err) -> ProblemResponses.write(ex, HttpStatus.FORBIDDEN,
								"Insufficient role for this operation")))
				.build();
	}

	@Bean
	ReactiveJwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwksUri,
			@Value("${gateway.jwt.issuer}") String issuer, @Value("${gateway.jwt.audience}") String audience) {
		NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build();
		OAuth2TokenValidator<Jwt> audienceCheck = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceCheck));
		return decoder;
	}

	private static ReactiveJwtAuthenticationConverter converter() {
		ReactiveJwtAuthenticationConverter c = new ReactiveJwtAuthenticationConverter();
		c.setJwtGrantedAuthoritiesConverter(jwt -> {
			List<GrantedAuthority> out = new ArrayList<>();
			String role = jwt.getClaimAsString("role");
			if (role != null) {
				out.add(new SimpleGrantedAuthority("ROLE_" + role));
			}
			Collection<String> scopes = jwt.getClaimAsStringList("scopes");
			if (scopes != null) {
				scopes.forEach(s -> out.add(new SimpleGrantedAuthority("SCOPE_" + s)));
			}
			return Flux.fromIterable(out);
		});
		return c;
	}
}
