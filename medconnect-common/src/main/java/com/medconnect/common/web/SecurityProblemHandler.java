package com.medconnect.common.web;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.medconnect.common.messaging.OutboxWriter;

/**
 * Method-security exceptions ({@code @PreAuthorize}) reach MVC before Spring Security's filter; map them here so the
 * catch-all in {@link ProblemExceptionHandler} does not turn a 403 into a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityProblemHandler {

	@ExceptionHandler(AccessDeniedException.class)
	ProblemDetail accessDenied() {
		return problem(HttpStatus.FORBIDDEN, "Access denied");
	}

	@ExceptionHandler(AuthenticationException.class)
	ProblemDetail unauthenticated() {
		return problem(HttpStatus.UNAUTHORIZED, "Missing or invalid token");
	}

	private static ProblemDetail problem(HttpStatus status, String title) {
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setType(java.net.URI.create("about:blank"));
		problem.setTitle(title);
		String correlationId = MDC.get(OutboxWriter.CORRELATION_ID_MDC);
		if (correlationId != null) {
			problem.setProperty("correlationId", correlationId);
		}
		return problem;
	}
}
