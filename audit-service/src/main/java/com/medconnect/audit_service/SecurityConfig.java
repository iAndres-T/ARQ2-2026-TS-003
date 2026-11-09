package com.medconnect.audit_service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import com.medconnect.common.security.SecurityDefaults;

@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain chain(HttpSecurity http) throws Exception {
		return SecurityDefaults.apply(http, auth -> auth
			.requestMatchers("/api/v1/audit/**").hasAllAuthorities("ROLE_MEDICAL_STAFF", "SCOPE_audit:read")).build();
	}
}
