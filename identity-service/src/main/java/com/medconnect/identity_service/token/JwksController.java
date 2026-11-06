package com.medconnect.identity_service.token;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwksController {

	private final SigningKeyStore keys;

	public JwksController(SigningKeyStore keys) {
		this.keys = keys;
	}

	@GetMapping("/api/v1/.well-known/jwks.json")
	public Map<String, Object> jwks() {
		return keys.publicKeys().toJSONObject();
	}
}
