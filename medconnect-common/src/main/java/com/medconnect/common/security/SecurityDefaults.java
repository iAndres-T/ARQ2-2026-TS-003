package com.medconnect.common.security;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;

import com.medconnect.common.messaging.OutboxWriter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Baseline for every resource-server service (ADR-005): stateless, JWT validated locally against the JWKS,
 * health and metrics open, service-specific {@code rules} next, everything else authenticated. 401/403 are
 * problem+json.
 */
public final class SecurityDefaults {

	private SecurityDefaults() {
	}

	public static HttpSecurity apply(HttpSecurity http,
			Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> rules) {
		return http.csrf(csrf -> csrf.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> {
				auth.requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll();
				rules.customize(auth);
				auth.anyRequest().authenticated();
			})
			.oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(new MedConnectJwtConverter()))
				.authenticationEntryPoint((req, res, ex) -> problem(res, HttpStatus.UNAUTHORIZED, "Missing or invalid token"))
				.accessDeniedHandler((req, res, ex) -> problem(res, HttpStatus.FORBIDDEN, "Access denied")))
			.exceptionHandling(e -> e
				.authenticationEntryPoint((req, res, ex) -> problem(res, HttpStatus.UNAUTHORIZED, "Missing or invalid token"))
				.accessDeniedHandler((req, res, ex) -> problem(res, HttpStatus.FORBIDDEN, "Access denied")));
	}

	private static void problem(HttpServletResponse response, HttpStatus status, String title) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		String correlationId = MDC.get(OutboxWriter.CORRELATION_ID_MDC);
		response.getWriter()
			.write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status.value()
					+ (correlationId == null ? "" : ",\"correlationId\":\"" + correlationId + "\"") + "}");
	}
}
