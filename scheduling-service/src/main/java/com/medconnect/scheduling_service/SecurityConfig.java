package com.medconnect.scheduling_service;

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
			.requestMatchers(HttpMethod.POST, "/api/v1/shifts").hasAllAuthorities("ROLE_MEDICAL_STAFF", "SCOPE_scheduling:manage")
			.requestMatchers("/api/v1/shifts", "/api/v1/doctors/**").hasRole("MEDICAL_STAFF")).build();
	}
}
