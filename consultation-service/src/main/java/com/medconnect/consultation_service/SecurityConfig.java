package com.medconnect.consultation_service;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import com.medconnect.common.security.SecurityDefaults;

@Configuration
@EnableCaching
class SecurityConfig {

	@Bean
	SecurityFilterChain chain(HttpSecurity http) throws Exception {
		// GET is any authenticated caller; ownership / assigned-doctor checks live in the controller.
		return SecurityDefaults.apply(http, auth -> auth
			.requestMatchers(HttpMethod.POST, "/api/v1/consultations", "/api/v1/consultations/*/cancel").hasRole("PATIENT")
			.requestMatchers(HttpMethod.PUT, "/api/v1/consultations/*/symptoms").hasRole("PATIENT")
			.requestMatchers(HttpMethod.POST, "/api/v1/consultations/*/join").hasRole("PATIENT")
			.requestMatchers(HttpMethod.POST, "/api/v1/consultations/*/start", "/api/v1/consultations/*/complete").hasRole("MEDICAL_STAFF")
			// never routed by the gateway; guarded by the X-Internal-Token check in InternalSnapshotController
			.requestMatchers(HttpMethod.GET, "/internal/v1/consultations/waiting").permitAll())
			.build();
	}
}
