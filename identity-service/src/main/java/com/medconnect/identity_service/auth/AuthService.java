package com.medconnect.identity_service.auth;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.medconnect.common.web.ApiException;
import com.medconnect.identity_service.refresh.RefreshToken;
import com.medconnect.identity_service.refresh.RefreshTokenRepository;
import com.medconnect.identity_service.token.TokenIssuer;
import com.medconnect.identity_service.user.UserAccount;
import com.medconnect.identity_service.user.UserRepository;

@Service
public class AuthService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;
	private final UserRepository users;
	private final RefreshTokenRepository refreshTokens;
	private final TokenIssuer issuer;
	private final PasswordPolicy policy;
	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
	private final Duration refreshTtl;
	private final int maxFailed;
	private final Duration lockout;
	// unknown emails cost the same as wrong passwords (no user enumeration by timing)
	private final String dummyHash = encoder.encode("not-a-real-password");

	public AuthService(JdbcTemplate jdbc, UserRepository users, RefreshTokenRepository refreshTokens, TokenIssuer issuer, PasswordPolicy policy,
			@Value("${identity.refresh-token-ttl}") Duration refreshTtl, @Value("${identity.max-failed-logins}") int maxFailed,
			@Value("${identity.lockout}") Duration lockout) {
		this.jdbc = jdbc;
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.issuer = issuer;
		this.policy = policy;
		this.refreshTtl = refreshTtl;
		this.maxFailed = maxFailed;
		this.lockout = lockout;
	}

	public Map<String, Object> register(String email, String password, String fullName, LocalDate birthDate) {
		policy.check(password);
		String normalized = email.trim().toLowerCase();
		if (users.findByEmail(normalized).isPresent()) {
			throw conflict();
		}
		try {
			UserAccount u = users.saveAndFlush(UserAccount.patient(normalized, encoder.encode(password), fullName, birthDate));
			return Map.of("patientId", u.getId(), "email", u.getEmail(), "role", u.getRole());
		}
		catch (DataIntegrityViolationException e) {
			throw conflict();
		}
	}

	// Deliberately not @Transactional: the failure counter must persist although we throw.
	public Map<String, Object> login(String email, String password) {
		UserAccount u = users.findByEmail(email.trim().toLowerCase()).orElse(null);
		Instant now = Instant.now();
		if (u == null) {
			encoder.matches(password, dummyHash);
			throw invalid();
		}
		if (u.isLocked(now)) {
			throw locked();
		}
		if (!encoder.matches(password, u.getPasswordHash())) {
			// atomic increment: concurrent wrong passwords can never lose a count
			Integer failed = jdbc.queryForObject("""
					update users set failed_attempts = failed_attempts + 1,
					  locked_until = case when failed_attempts + 1 >= ? then ? else locked_until end
					where id = ? returning failed_attempts""", Integer.class, maxFailed, Timestamp.from(now.plus(lockout)), u.getId());
			throw failed != null && failed >= maxFailed ? locked() : invalid();
		}
		jdbc.update("update users set failed_attempts = 0, locked_until = null where id = ?", u.getId());
		return tokens(u);
	}

	public Map<String, Object> refresh(String refreshToken) {
		String hash = sha256(refreshToken);
		RefreshToken stored = refreshTokens.findByTokenHash(hash).orElseThrow(AuthService::badRefresh);
		if (stored.getExpiresAt().isBefore(Instant.now()) || refreshTokens.markUsed(hash) != 1) {
			throw badRefresh();
		}
		return tokens(users.findById(stored.getUserId()).orElseThrow(AuthService::badRefresh));
	}

	private Map<String, Object> tokens(UserAccount u) {
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		String refresh = "rt_" + HexFormat.of().formatHex(raw);
		refreshTokens.save(new RefreshToken(sha256(refresh), u.getId(), Instant.now().plus(refreshTtl)));
		return Map.of("accessToken", issuer.issue(u), "refreshToken", refresh, "tokenType", "Bearer",
				"expiresIn", issuer.expiresInSeconds(), "role", u.getRole());
	}

	private static String sha256(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static ApiException conflict() {
		return new ApiException(HttpStatus.CONFLICT, "Email already registered", "An account with this email already exists");
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials", "Email or password is incorrect");
	}

	private static ApiException locked() {
		return new ApiException(HttpStatus.LOCKED, "Account locked", "Too many failed attempts, try again later");
	}

	private static ApiException badRefresh() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token", "Refresh token is invalid, expired or revoked");
	}
}
