package com.medconnect.recording_service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import com.medconnect.common.security.SecurityDefaults;

@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain chain(HttpSecurity http) throws Exception {
		return SecurityDefaults.apply(http, auth -> auth
			// authenticated by the HMAC signature instead of a JWT
			.requestMatchers(HttpMethod.POST, "/api/v1/webhooks/video-provider").permitAll()
			.requestMatchers(HttpMethod.GET, "/api/v1/consultations/*/recording")
			.access(org.springframework.security.authorization.AuthorizationManagers.allOf(
				org.springframework.security.authorization.AuthorityAuthorizationManager.hasRole("MEDICAL_STAFF"),
				org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_recordings:read"))))
			.build();
	}
}
