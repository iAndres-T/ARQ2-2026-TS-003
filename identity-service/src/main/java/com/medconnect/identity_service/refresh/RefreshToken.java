package com.medconnect.identity_service.refresh;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

	@Id
	@Column(name = "token_hash")
	private String tokenHash;
	@Column(name = "user_id")
	private UUID userId;
	@Column(name = "expires_at")
	private Instant expiresAt;
	private boolean used;

	protected RefreshToken() {
	}

	public RefreshToken(String tokenHash, UUID userId, Instant expiresAt) {
		this.tokenHash = tokenHash;
		this.userId = userId;
		this.expiresAt = expiresAt;
	}

	public UUID getUserId() { return userId; }
	public Instant getExpiresAt() { return expiresAt; }
}
