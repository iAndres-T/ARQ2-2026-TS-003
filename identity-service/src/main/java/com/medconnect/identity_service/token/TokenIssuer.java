package com.medconnect.identity_service.token;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.medconnect.identity_service.user.UserAccount;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@Component
public class TokenIssuer {

	public static final String ISSUER = "medconnect-identity";
	public static final String AUDIENCE = "medconnect-api";

	private final SigningKeyStore keys;
	private final Duration ttl;

	public TokenIssuer(SigningKeyStore keys, @Value("${identity.access-token-ttl}") Duration ttl) {
		this.keys = keys;
		this.ttl = ttl;
	}

	public long expiresInSeconds() {
		return ttl.toSeconds();
	}

	public String issue(UserAccount user) {
		Instant now = Instant.now();
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
			.issuer(ISSUER)
			.audience(AUDIENCE)
			.subject(user.getId().toString())
			.jwtID(UUID.randomUUID().toString())
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plus(ttl)))
			.claim("role", user.getRole())
			.claim("scopes", user.scopeList());
		if (user.getDoctorId() != null) {
			claims.claim("doctorId", user.getDoctorId().toString());
		}
		try {
			RSAKey key = keys.current();
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}
}
