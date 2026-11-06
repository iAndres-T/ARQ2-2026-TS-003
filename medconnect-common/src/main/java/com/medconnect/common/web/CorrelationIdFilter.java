package com.medconnect.common.web;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import com.medconnect.common.messaging.OutboxWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Accepts {@code X-Correlation-Id} (or generates one), exposes it in the MDC and echoes it in the response. */
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Correlation-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String correlationId = validOrNew(request.getHeader(HEADER));
		MDC.put(OutboxWriter.CORRELATION_ID_MDC, correlationId);
		response.setHeader(HEADER, correlationId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(OutboxWriter.CORRELATION_ID_MDC);
		}
	}

	private static String validOrNew(String header) {
		if (header != null) {
			try {
				return UUID.fromString(header.trim()).toString();
			}
			catch (IllegalArgumentException ignored) {
				// not a UUID: replace it rather than propagating arbitrary client input into events
			}
		}
		return UUID.randomUUID().toString();
	}
}
