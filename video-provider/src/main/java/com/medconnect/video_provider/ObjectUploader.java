package com.medconnect.video_provider;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;

/** Provider-side direct upload to the shared bucket (ADR-003), with a SHA-256 checksum the recording-service reads back. */
@Component
class ObjectUploader {

	private final S3Client s3;
	private final String bucket;

	ObjectUploader(@Value("${video-provider.storage.endpoint}") String endpoint, @Value("${video-provider.storage.access-key}") String accessKey,
			@Value("${video-provider.storage.secret-key}") String secretKey, @Value("${video-provider.storage.bucket}") String bucket) {
		this.bucket = bucket;
		this.s3 = S3Client.builder()
			.endpointOverride(URI.create(endpoint))
			.region(Region.US_EAST_1)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
			.forcePathStyle(true)
			.build();
	}

	void upload(String key, byte[] content) {
		s3.putObject(p -> p.bucket(bucket).key(key).checksumAlgorithm(ChecksumAlgorithm.SHA256), RequestBody.fromBytes(content));
	}
}
