package com.medconnect.recording_service.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "recordings")
public class Recording {

	@Id
	public UUID id;
	@Column(name = "consultation_id")
	public UUID consultationId;
	@Column(name = "provider_recording_id")
	public String providerRecordingId;
	public String status;
	public String url;
	@Column(name = "duration_seconds")
	public Integer durationSeconds;
	@Column(name = "recorded_at")
	public Instant recordedAt;
	@Column(name = "size_bytes")
	public Long sizeBytes;
	@Column(name = "checksum_sha256")
	public String checksumSha256;
	public int attempts;
	@Column(name = "failure_reason")
	public String failureReason;
	@Column(name = "created_at", insertable = false, updatable = false)
	public Instant createdAt;

	protected Recording() {
	}

	public Recording(UUID id, UUID consultationId, String providerRecordingId, String status) {
		this.id = id;
		this.consultationId = consultationId;
		this.providerRecordingId = providerRecordingId;
		this.status = status;
	}
}
