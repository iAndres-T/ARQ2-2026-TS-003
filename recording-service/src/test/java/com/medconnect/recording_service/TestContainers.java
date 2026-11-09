package com.medconnect.recording_service;

import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestContainers {

	static final String USER = "medconnect";
	static final String PASSWORD = "medconnect-secret";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer("postgres:16-alpine");
	}

	@Bean
	@ServiceConnection
	RabbitMQContainer rabbit() {
		return new RabbitMQContainer("rabbitmq:4-management-alpine");
	}

	@Bean
	GenericContainer<?> minio() {
		return new GenericContainer<>("bitnamilegacy/minio:2025.7.23").withExposedPorts(9000)
			.withEnv("MINIO_ROOT_USER", USER)
			.withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
			.waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofSeconds(90)));
	}

	@Bean
	DynamicPropertyRegistrar minioProperties(GenericContainer<?> minio) {
		return registry -> {
			String url = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
			registry.add("medconnect.storage.endpoint", () -> url);
			registry.add("medconnect.storage.public-url", () -> url);
			registry.add("medconnect.storage.access-key", () -> USER);
			registry.add("medconnect.storage.secret-key", () -> PASSWORD);
		};
	}
}
