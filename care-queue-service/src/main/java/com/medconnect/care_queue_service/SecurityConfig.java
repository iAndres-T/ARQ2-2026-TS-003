package com.medconnect.care_queue_service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import com.medconnect.common.security.SecurityDefaults;

@Configuration
class SecurityConfig {

	/** Queue endpoints are for MEDICAL_STAFF (spec §2); the doctorId claim is checked by the claim endpoint. */
	@Bean
	SecurityFilterChain chain(HttpSecurity http) throws Exception {
		return SecurityDefaults.apply(http, auth -> auth.requestMatchers("/api/v1/queue/**").hasRole("MEDICAL_STAFF"))
			.build();
	}
}
