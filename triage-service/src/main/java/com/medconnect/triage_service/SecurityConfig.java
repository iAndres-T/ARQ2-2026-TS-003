package com.medconnect.triage_service;

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
		// GET is any authenticated caller; patient ownership is checked in the controller.
		return SecurityDefaults.apply(http, auth -> auth
			.requestMatchers(HttpMethod.POST, "/api/v1/triage/consultations/*/override").hasRole("MEDICAL_STAFF"))
			.build();
	}
}
