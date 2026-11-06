package com.medconnect.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class MedConnectJwtConverterTest {

	private final MedConnectJwtConverter converter = new MedConnectJwtConverter();

	@Test
	void scopesAndRoleBecomeAuthorities() {
		Jwt jwt = jwt(UUID.randomUUID(), "MEDICAL_STAFF", UUID.randomUUID(), List.of("audit:read"));

		var auth = converter.convert(jwt);

		assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
			.containsExactlyInAnyOrder("ROLE_MEDICAL_STAFF", "SCOPE_audit:read");
		assertThat(auth.getName()).isEqualTo(jwt.getSubject());
	}

	@Test
	void patientWithoutScopesGetsOnlyRole() {
		var auth = converter.convert(jwt(UUID.randomUUID(), "PATIENT", null, null));

		assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_PATIENT");
	}

	@Test
	void currentUserReadsClaims() {
		UUID sub = UUID.randomUUID();
		UUID doctorId = UUID.randomUUID();

		CurrentUser user = CurrentUser.from(jwt(sub, "MEDICAL_STAFF", doctorId, List.of("recordings:read")));

		assertThat(user).isEqualTo(new CurrentUser(sub, "MEDICAL_STAFF", doctorId, Set.of("recordings:read")));
		assertThat(user.isStaff()).isTrue();
		assertThat(user.hasScope("recordings:read")).isTrue();
		assertThat(user.hasScope("audit:read")).isFalse();
	}

	private static Jwt jwt(UUID sub, String role, UUID doctorId, List<String> scopes) {
		Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject(sub.toString()).claim("role", role);
		if (doctorId != null) {
			builder.claim("doctorId", doctorId.toString());
		}
		if (scopes != null) {
			builder.claim("scopes", scopes);
		}
		return builder.build();
	}
}
