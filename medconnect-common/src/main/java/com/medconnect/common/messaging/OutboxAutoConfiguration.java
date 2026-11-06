package com.medconnect.common.messaging;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import tools.jackson.databind.json.JsonMapper;

/** Outbox and inbox for services with a relational database; services without one (care-queue) skip it. */
@AutoConfiguration(after = MessagingAutoConfiguration.class, afterName = {
		"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
		"org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
		"org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration" })
@ConditionalOnClass({ JdbcTemplate.class, RabbitTemplate.class })
@ConditionalOnSingleCandidate(DataSource.class)
public class OutboxAutoConfiguration {

	@Bean
	OutboxWriter outboxWriter(JdbcTemplate jdbc, JsonMapper json, Environment env, ObjectProvider<Tracer> tracer) {
		return new OutboxWriter(jdbc, json, env.getRequiredProperty("spring.application.name"), tracer);
	}

	@Bean
	OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager txManager, RabbitTemplate rabbit,
			List<OutboxPublishedCallback> callbacks, MessagingProperties properties, ObjectProvider<MeterRegistry> meters) {
		meters.ifAvailable(registry -> {
			Gauge.builder("medconnect.outbox.pending", () -> count(jdbc, "published_at is null")).register(registry);
			Gauge.builder("medconnect.outbox.unroutable", () -> count(jdbc, "published_at is null and last_error = 'unroutable'"))
				.register(registry);
		});
		return new OutboxRelay(jdbc, new TransactionTemplate(txManager), rabbit, callbacks,
				properties.outboxPollInterval());
	}

	private static long count(JdbcTemplate jdbc, String where) {
		Long n = jdbc.queryForObject("select count(*) from outbox where " + where, Long.class);
		return (n == null) ? 0 : n;
	}

	@Bean
	OutboxPurgeJob outboxPurgeJob(JdbcTemplate jdbc) {
		return new OutboxPurgeJob(jdbc);
	}

	@Bean
	InboxGuard inboxGuard(JdbcTemplate jdbc) {
		return new InboxGuard(jdbc);
	}
}
