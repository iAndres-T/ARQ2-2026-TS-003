package com.medconnect.audit_service;

import java.nio.file.Path;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

/** Same two-user setup as compose: the real init script creates audit_app; Flyway runs as the owner. */
@TestConfiguration(proxyBeanMethods = false)
class TestContainers {

	@Bean
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer("postgres:16-alpine").withCopyFileToContainer(
				MountableFile.forHostPath(Path.of("..", "infra", "audit-db", "init", "01-app-user.sql").toAbsolutePath().normalize()),
				"/docker-entrypoint-initdb.d/01-app-user.sql");
	}

	@Bean
	@ServiceConnection
	RabbitMQContainer rabbit() {
		return new RabbitMQContainer("rabbitmq:4-management-alpine");
	}

	@Bean
	DynamicPropertyRegistrar auditDbProperties(PostgreSQLContainer pg) {
		return registry -> {
			registry.add("spring.datasource.url", pg::getJdbcUrl);
			registry.add("spring.datasource.username", () -> "audit_app");
			registry.add("spring.datasource.password", () -> "audit_app");
			registry.add("spring.flyway.url", pg::getJdbcUrl);
			registry.add("spring.flyway.user", pg::getUsername);
			registry.add("spring.flyway.password", pg::getPassword);
		};
	}
}
