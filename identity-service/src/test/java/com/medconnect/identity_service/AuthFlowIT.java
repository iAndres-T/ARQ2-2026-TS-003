package com.medconnect.identity_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(AuthFlowIT.Containers.class)
class AuthFlowIT {

	@TestConfiguration(proxyBeanMethods = false)
	static class Containers {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer("postgres:16-alpine");
		}
	}

	static final String STRONG = "S3cure-Passw0rd";
	static final String WRONG = "Wrong-Passw0rd1";
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	WebApplicationContext ctx;
	@Autowired
	JdbcTemplate jdbc;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	MockHttpServletResponse postJson(String path, String body) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
	}

	MockHttpServletResponse register(String email, String password) throws Exception {
		return postJson("/api/v1/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + password
				+ "\",\"fullName\":\"Ana Torres\",\"birthDate\":\"1968-04-12\"}");
	}

	MockHttpServletResponse login(String email, String password) throws Exception {
		return postJson("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	JsonNode body(MockHttpServletResponse r) throws Exception {
		return JSON.readTree(r.getContentAsString());
	}

	// S-F1-01
	@Test
	void registerCreatesPatient() throws Exception {
		MockHttpServletResponse r = register("ana@example.com", STRONG);
		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(body(r).get("role").asString()).isEqualTo("PATIENT");
	}

	@Test
	void duplicateEmailIs409() throws Exception {
		register("dup@example.com", STRONG);
		assertThat(register("dup@example.com", STRONG).getStatus()).isEqualTo(409);
	}

	@Test
	void weakPasswordIs422() throws Exception {
		assertThat(register("weak@example.com", "short").getStatus()).isEqualTo(422);
	}

	// S-F1-01
	@Test
	void loginReturnsRs256TokenWithRole() throws Exception {
		String patientId = body(register("login@example.com", STRONG)).get("patientId").asString();
		MockHttpServletResponse r = login("login@example.com", STRONG);
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(body(r).get("refreshToken").asString()).startsWith("rt_");
		SignedJWT jwt = SignedJWT.parse(body(r).get("accessToken").asString());
		JWKSet jwks = JWKSet.parse(mvc.perform(get("/api/v1/.well-known/jwks.json")).andReturn().getResponse().getContentAsString());
		assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
		assertThat(jwt.verify(new RSASSAVerifier(jwks.getKeyByKeyId(jwt.getHeader().getKeyID()).toRSAKey()))).isTrue();
		var claims = jwt.getJWTClaimsSet();
		assertThat(claims.getStringClaim("role")).isEqualTo("PATIENT");
		assertThat(claims.getSubject()).isEqualTo(patientId);
		assertThat(claims.getIssuer()).isEqualTo("medconnect-identity");
		assertThat(claims.getAudience()).containsExactly("medconnect-api");
		assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(3_600_000L);
	}

	// S-F1-02
	@Test
	void invalidCredentialsIs401NoToken() throws Exception {
		register("bad@example.com", STRONG);
		MockHttpServletResponse r = login("bad@example.com", WRONG);
		assertThat(r.getStatus()).isEqualTo(401);
		assertThat(r.getContentAsString()).doesNotContain("accessToken");
	}

	@Test
	void fifthFailedLoginLocksWith423() throws Exception {
		register("lock@example.com", STRONG);
		for (int i = 0; i < 4; i++) {
			assertThat(login("lock@example.com", WRONG).getStatus()).isEqualTo(401);
		}
		assertThat(login("lock@example.com", WRONG).getStatus()).isEqualTo(423);
		assertThat(login("lock@example.com", STRONG).getStatus()).isEqualTo(423);
	}

	@Test
	void refreshRotatesToken() throws Exception {
		register("rot@example.com", STRONG);
		String old = body(login("rot@example.com", STRONG)).get("refreshToken").asString();
		MockHttpServletResponse r = postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + old + "\"}");
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(body(r).get("refreshToken").asString()).isNotEqualTo(old);
		assertThat(postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + old + "\"}").getStatus()).isEqualTo(401);
	}

	@Test
	void seededDoctorHasDoctorIdAndScopes() throws Exception {
		MockHttpServletResponse r = login("doctor1@medconnect.test", "Doctor!Passw0rd1");
		assertThat(r.getStatus()).isEqualTo(200);
		var claims = SignedJWT.parse(body(r).get("accessToken").asString()).getJWTClaimsSet();
		assertThat(body(r).get("role").asString()).isEqualTo("MEDICAL_STAFF");
		assertThat(claims.getStringClaim("doctorId")).isNotNull();
		assertThat(claims.getStringListClaim("scopes")).contains("audit:read");
	}

	@Test
	void concurrentFailuresStillLock() throws Exception {
		register("race@example.com", STRONG);
		ExecutorService pool = Executors.newFixedThreadPool(10);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			Callable<Integer> c = () -> login("race@example.com", WRONG).getStatus();
			results.add(pool.submit(c));
		}
		for (Future<Integer> f : results) {
			f.get();
		}
		pool.shutdown();
		assertThat(login("race@example.com", STRONG).getStatus()).isEqualTo(423);
	}

	@Test
	void unknownRefreshTokenIs401() throws Exception {
		assertThat(postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"rt_nope\"}").getStatus()).isEqualTo(401);
	}

	@Test
	void expiredRefreshTokenIs401() throws Exception {
		register("exp@example.com", STRONG);
		String rt = body(login("exp@example.com", STRONG)).get("refreshToken").asString();
		jdbc.update("update refresh_tokens set expires_at = now() - interval '1 minute'");
		assertThat(postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}").getStatus()).isEqualTo(401);
	}

	@Test
	void jwksShapeHasOnlyPublicMembers() throws Exception {
		JsonNode key = JSON.readTree(mvc.perform(get("/api/v1/.well-known/jwks.json")).andReturn().getResponse().getContentAsString())
			.get("keys").get(0);
		assertThat(key.get("kty").asString()).isEqualTo("RSA");
		assertThat(key.get("use").asString()).isEqualTo("sig");
		assertThat(key.get("alg").asString()).isEqualTo("RS256");
		assertThat(key.get("kid").asString()).isNotBlank();
		assertThat(key.has("n")).isTrue();
		assertThat(key.has("e")).isTrue();
		for (String priv : List.of("d", "p", "q", "dp", "dq", "qi")) {
			assertThat(key.has(priv)).as(priv).isFalse();
		}
	}

	@Test
	void invalidEmailIs422ProblemJson() throws Exception {
		MockHttpServletResponse r = register("not-an-email", STRONG);
		assertThat(r.getStatus()).isEqualTo(422);
		assertThat(r.getContentType()).contains("application/problem+json");
	}

	@Test
	void malformedJsonIs400ProblemJson() throws Exception {
		MockHttpServletResponse r = postJson("/api/v1/auth/login", "{not json");
		assertThat(r.getStatus()).isEqualTo(400);
		assertThat(r.getContentType()).contains("application/problem+json");
	}

	@Test
	void lockExpiresAfterLockoutWindow() throws Exception {
		register("exp-lock@example.com", STRONG);
		for (int i = 0; i < 5; i++) {
			login("exp-lock@example.com", WRONG);
		}
		assertThat(login("exp-lock@example.com", STRONG).getStatus()).isEqualTo(423);
		jdbc.update("update users set locked_until = now() - interval '1 minute' where email = 'exp-lock@example.com'");
		assertThat(login("exp-lock@example.com", STRONG).getStatus()).isEqualTo(200);
	}
}
