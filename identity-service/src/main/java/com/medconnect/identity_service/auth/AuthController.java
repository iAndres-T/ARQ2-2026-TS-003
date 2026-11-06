package com.medconnect.identity_service.auth;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	record RegisterRequest(@NotBlank @Email String email, @NotBlank String password, @NotBlank String fullName, LocalDate birthDate) {
	}

	record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

	record RefreshRequest(@NotBlank String refreshToken) {
	}

	private final AuthService auth;

	public AuthController(AuthService auth) {
		this.auth = auth;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> register(@Valid @RequestBody RegisterRequest r) {
		return auth.register(r.email(), r.password(), r.fullName(), r.birthDate());
	}

	@PostMapping("/login")
	Map<String, Object> login(@Valid @RequestBody LoginRequest r) {
		return auth.login(r.email(), r.password());
	}

	@PostMapping("/refresh")
	Map<String, Object> refresh(@Valid @RequestBody RefreshRequest r) {
		return auth.refresh(r.refreshToken());
	}
}
