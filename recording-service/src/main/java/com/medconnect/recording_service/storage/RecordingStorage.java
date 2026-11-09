package com.medconnect.recording_service.storage;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Object storage access (D9). The service never touches media bytes: copy is server-side, reads are pre-signed. */
@Component
public class RecordingStorage {

	public record StoredObject(String uri, long sizeBytes, String sha256Hex) {
	}

	private final S3Client s3;
	private final S3Presigner presigner;
	private final String bucket;
	private final Duration ttl;

	RecordingStorage(S3Client s3, S3Presigner presigner, @Value("${medconnect.storage.bucket}") String bucket,
			@Value("${medconnect.recording.presign-ttl}") Duration ttl) {
		this.s3 = s3;
		this.presigner = presigner;
		this.bucket = bucket;
		this.ttl = ttl;
	}

	/** Copies {@code incomingKey} to the canonical key and reads size and SHA-256 back from the stored object. */
	public StoredObject promote(String incomingKey, UUID consultationId, UUID recordingId) {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		String key = "recordings/%d/%02d/%s/%s.mp4".formatted(today.getYear(), today.getMonthValue(), consultationId, recordingId);
		s3.copyObject(c -> c.sourceBucket(bucket).sourceKey(incomingKey).destinationBucket(bucket).destinationKey(key)
			.checksumAlgorithm(ChecksumAlgorithm.SHA256));
		HeadObjectResponse head = s3.headObject(h -> h.bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED));
		if (head.checksumSHA256() == null) {
			throw new IllegalStateException("Stored object has no SHA-256 checksum");
		}
		String hex = HexFormat.of().formatHex(Base64.getDecoder().decode(head.checksumSHA256()));
		return new StoredObject("s3://" + bucket + "/" + key, head.contentLength(), hex);
	}

	/** Pre-signed GET (TTL 300 s) for an {@code s3://bucket/key} URI. */
	public String presign(String uri) {
		String key = uri.substring(("s3://" + bucket + "/").length());
		return presigner.presignGetObject(r -> r.signatureDuration(ttl)
			.getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())).url().toString();
	}
}
