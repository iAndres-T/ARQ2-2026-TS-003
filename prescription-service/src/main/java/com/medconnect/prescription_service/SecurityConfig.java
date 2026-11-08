package com.medconnect.prescription_service;

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
		// GET is open to doctors and patients; the service checks issuer / owner
		return SecurityDefaults.apply(http, auth -> auth
			.requestMatchers(HttpMethod.POST, "/api/v1/prescriptions").hasRole("MEDICAL_STAFF")).build();
	}
}
