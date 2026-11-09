package com.medconnect.recording_service.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 over the exact request bytes, compared in constant time. */
public final class SignatureVerifier {

	private SignatureVerifier() {
	}

	public static boolean verify(byte[] rawBody, String headerHex, String secret) {
		if (headerHex == null) {
			return false;
		}
		return MessageDigest.isEqual(hmacHex(rawBody, secret).getBytes(StandardCharsets.UTF_8),
				headerHex.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
	}

	public static String hmacHex(byte[] rawBody, String secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(rawBody));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
