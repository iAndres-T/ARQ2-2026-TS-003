package com.medconnect.consultation_service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestContainers {

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
	@ServiceConnection(name = "redis")
	GenericContainer<?> redis() {
		return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	}
}
