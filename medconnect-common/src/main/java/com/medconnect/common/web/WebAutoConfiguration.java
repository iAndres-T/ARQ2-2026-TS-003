package com.medconnect.common.web;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.observation.ObservationPredicate;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration(afterName = {
		"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
		"org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
		"org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
		"org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration" })
public class WebAutoConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	static class ServletWebConfiguration {

		/** Health checks and Prometheus scrapes would otherwise flood Jaeger with one trace each. */
		@Bean
		ObservationPredicate skipActuatorObservations() {
			return (name, context) -> !(context instanceof ServerRequestObservationContext server
					&& server.getCarrier().getRequestURI().startsWith("/actuator"));
		}

		@Bean
		ProblemExceptionHandler problemExceptionHandler() {
			return new ProblemExceptionHandler();
		}

		/** Runs before Spring Security so 401/403 responses also carry the correlation id. */
		@Bean
		FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
			FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
			registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
			return registration;
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(AccessDeniedException.class)
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	static class SecurityProblemConfiguration {

		@Bean
		SecurityProblemHandler securityProblemHandler() {
			return new SecurityProblemHandler();
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(JdbcTemplate.class)
	@ConditionalOnSingleCandidate(DataSource.class)
	static class JdbcIdempotencyConfiguration {

		@Bean
		@ConditionalOnMissingBean
		IdempotencyStore idempotencyStore(JdbcTemplate jdbc) {
			return new JdbcIdempotencyStore(jdbc);
		}

		@Bean
		@ConditionalOnMissingBean
		IdempotencyService idempotencyService(IdempotencyStore store, JsonMapper json, PlatformTransactionManager txManager) {
			return new IdempotencyService(store, json, new TransactionTemplate(txManager));
		}
	}
}
