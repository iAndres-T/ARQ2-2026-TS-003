package com.medconnect.consultation_service.video;

/** MedConnect's view of a provider session (ACL output): the provider's own model never leaves {@link VideoProviderClient}. */
public record VideoSession(String sessionId, String patientJoinUrl, String doctorJoinUrl) {
}
