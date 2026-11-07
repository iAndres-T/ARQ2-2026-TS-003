package com.medconnect.consultation_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** D11: the cache is an optimization; a Redis outage must degrade to DB reads and never fail a committed write. */
// Redis timeouts are deliberately NOT overridden here: the application.yml defaults must keep reads fast.
@SpringBootTest(properties = { "spring.data.redis.host=localhost", "spring.data.redis.port=1" })
@Import(CacheOutageIT.NoRedis.class)
class CacheOutageIT {

	@TestConfiguration(proxyBeanMethods = false)
	static class NoRedis {
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
	}

	@Autowired
	WebApplicationContext ctx;
	@Autowired
	JdbcTemplate jdbc;

	static RequestPostProcessor patient(UUID id) {
		return jwt().jwt(j -> j.subject(id.toString()).claim("role", "PATIENT")).authorities(new SimpleGrantedAuthority("ROLE_PATIENT"));
	}

	@Test
	void readsAndCommittedWritesSurviveRedisOutage() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
		UUID p = UUID.randomUUID();
		UUID id = UUID.randomUUID();
		jdbc.update("insert into consultations(id, patient_id, status, symptoms, requested_at, version) values (?, ?, 'WAITING', '{}', now(), 1)", id, p);

		long start = System.nanoTime();
		assertThat(mvc.perform(get("/api/v1/consultations/" + id).with(patient(p))).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(3));
		assertThat(mvc.perform(post("/api/v1/consultations/" + id + "/cancel").with(patient(p))).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(jdbc.queryForObject("select status from consultations where id = ?", String.class, id)).isEqualTo("CANCELLED");
	}
}
