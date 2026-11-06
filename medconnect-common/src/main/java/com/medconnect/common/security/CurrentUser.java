package com.medconnect.common.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/** Caller identity from the JWT; for patients {@code sub} is the patientId (spec §2). */
public record CurrentUser(UUID sub, String role, UUID doctorId, Set<String> scopes) {

	public static final String PATIENT = "PATIENT";
	public static final String MEDICAL_STAFF = "MEDICAL_STAFF";
	static final String ROLE_CLAIM = "role";
	static final String SCOPES_CLAIM = "scopes";
	static final String DOCTOR_ID_CLAIM = "doctorId";

	public static CurrentUser from(Jwt jwt) {
		String doctorId = jwt.getClaimAsString(DOCTOR_ID_CLAIM);
		List<String> scopes = jwt.getClaimAsStringList(SCOPES_CLAIM);
		return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString(ROLE_CLAIM),
				doctorId == null ? null : UUID.fromString(doctorId), scopes == null ? Set.of() : Set.copyOf(scopes));
	}

	public boolean isPatient() {
		return PATIENT.equals(role);
	}

	public boolean isStaff() {
		return MEDICAL_STAFF.equals(role);
	}

	public boolean hasScope(String scope) {
		return scopes.contains(scope);
	}
}
