package com.medconnect.identity_service.user;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class UserAccount {

	@Id
	private UUID id;
	private String email;
	@Column(name = "password_hash")
	private String passwordHash;
	@Column(name = "full_name")
	private String fullName;
	@Column(name = "birth_date")
	private LocalDate birthDate;
	private String role;
	@Column(name = "doctor_id")
	private UUID doctorId;
	private String scopes = "";
	@Column(name = "failed_attempts")
	private int failedAttempts;
	@Column(name = "locked_until")
	private Instant lockedUntil;

	protected UserAccount() {
	}

	public static UserAccount patient(String email, String passwordHash, String fullName, LocalDate birthDate) {
		UserAccount u = new UserAccount();
		u.id = UUID.randomUUID();
		u.email = email;
		u.passwordHash = passwordHash;
		u.fullName = fullName;
		u.birthDate = birthDate;
		u.role = "PATIENT";
		return u;
	}

	public List<String> scopeList() {
		return scopes.isBlank() ? List.of() : List.of(scopes.split(","));
	}

	public boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public UUID getId() { return id; }
	public String getEmail() { return email; }
	public String getPasswordHash() { return passwordHash; }
	public String getRole() { return role; }
	public UUID getDoctorId() { return doctorId; }
}
