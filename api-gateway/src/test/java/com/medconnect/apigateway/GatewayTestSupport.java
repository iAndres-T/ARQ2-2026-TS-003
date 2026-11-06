package com.medconnect.apigateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/** Test doubles: JDK HTTP servers as JWKS endpoint and backends, a Redis container and a token minter. */
final class GatewayTestSupport {

	record Received(String method, String path, Map<String, String> headers) {}

	static final RSAKey KEY = newKey();
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine");
	static final HttpServer JWKS = start();
	static final Backend DEFAULT = new Backend();
	static final Backend RECORDING = new Backend();

	static {
		REDIS.addExposedPort(6379);
		REDIS.start();
		Runtime.getRuntime().addShutdownHook(new Thread(REDIS::close));
		JWKS.createContext("/jwks", ex -> {
			byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes();
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(200, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
	}

	static final class Backend {
		final List<Received> requests = new CopyOnWriteArrayList<>();
		final HttpServer server = start();

		Backend() {
			server.createContext("/", ex -> {
				Map<String, String> headers = new HashMap<>();
				ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
				requests.add(new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers));
				byte[] body = "ok".getBytes();
				ex.sendResponseHeaders(200, body.length);
				ex.getResponseBody().write(body);
				ex.close();
			});
		}

		String url() {
			return "http://localhost:" + server.getAddress().getPort();
		}
	}

	static void properties(DynamicPropertyRegistry r) {
		r.add("spring.data.redis.host", REDIS::getHost);
		r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
				() -> "http://localhost:" + JWKS.getAddress().getPort() + "/jwks");
		for (String name : List.of("IDENTITY", "CONSULTATION", "TRIAGE", "QUEUE", "SCHEDULING", "PRESCRIPTION", "AUDIT")) {
			r.add("ROUTE_" + name + "_URI", DEFAULT::url);
		}
		r.add("ROUTE_RECORDING_URI", RECORDING::url);
	}

	static String token(String role, String audience, Instant expiresAt) {
		try {
			JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("medconnect-identity").audience(audience)
					.subject("user-" + role).claim("role", role).claim("scopes", List.of("consultations:write"))
					.issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expiresAt)).build();
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
			jwt.sign(new RSASSASigner(KEY));
			return jwt.serialize();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	static String token(String role) {
		return token(role, "medconnect-api", Instant.now().plusSeconds(300));
	}

	private static RSAKey newKey() {
		try {
			KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
			g.initialize(2048);
			KeyPair p = g.generateKeyPair();
			return new RSAKey.Builder((RSAPublicKey) p.getPublic()).privateKey(p.getPrivate()).keyID("test").build();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static HttpServer start() {
		try {
			HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			s.start();
			return s;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
