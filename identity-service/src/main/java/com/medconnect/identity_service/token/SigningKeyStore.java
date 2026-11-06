package com.medconnect.identity_service.token;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.YearMonth;
import java.util.Base64;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

/** RSA signing keys in identity_db; one is generated on first boot with kid yyyy-MM (D10). */
@Component
public class SigningKeyStore {

	private final JdbcTemplate jdbc;

	public SigningKeyStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		ensureKey();
	}

	private void ensureKey() {
		if (jdbc.queryForObject("select count(*) from signing_keys", Integer.class) > 0) {
			return;
		}
		try {
			KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
			gen.initialize(2048);
			KeyPair pair = gen.generateKeyPair();
			// ON CONFLICT: two replicas booting together must not fail
			jdbc.update("insert into signing_keys (kid, private_key, public_key) values (?, ?, ?) on conflict do nothing",
					YearMonth.now().toString(), Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
					Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private List<RSAKey> all() {
		return jdbc.query("select kid, private_key, public_key from signing_keys order by created_at desc, kid desc", (rs, i) -> {
			try {
				KeyFactory kf = KeyFactory.getInstance("RSA");
				RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(rs.getString("public_key"))));
				RSAPrivateKey priv = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(rs.getString("private_key"))));
				return new RSAKey.Builder(pub).privateKey(priv).keyID(rs.getString("kid")).keyUse(KeyUse.SIGNATURE)
					.algorithm(JWSAlgorithm.RS256).build();
			}
			catch (GeneralSecurityException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	/** Newest key, used for signing. */
	public RSAKey current() {
		return all().get(0);
	}

	/** Public parts of every key, so tokens signed with a previous key still validate. */
	public JWKSet publicKeys() {
		return new JWKSet(all().stream().<JWK>map(RSAKey::toPublicJWK).toList());
	}
}
