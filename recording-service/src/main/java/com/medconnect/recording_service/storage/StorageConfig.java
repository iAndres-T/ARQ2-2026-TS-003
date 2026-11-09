package com.medconnect.recording_service.storage;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
class StorageConfig {

	@Bean
	S3Client s3Client(@Value("${medconnect.storage.endpoint}") String endpoint, @Value("${medconnect.storage.access-key}") String ak,
			@Value("${medconnect.storage.secret-key}") String sk, @Value("${medconnect.storage.region}") String region) {
		return S3Client.builder()
			.endpointOverride(URI.create(endpoint))
			.region(Region.of(region))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ak, sk)))
			.forcePathStyle(true)
			.build();
	}

	/** Signs against the host-reachable public endpoint; the signature is bound to that host. */
	@Bean
	S3Presigner s3Presigner(@Value("${medconnect.storage.public-url}") String publicUrl, @Value("${medconnect.storage.access-key}") String ak,
			@Value("${medconnect.storage.secret-key}") String sk, @Value("${medconnect.storage.region}") String region) {
		return S3Presigner.builder()
			.endpointOverride(URI.create(publicUrl))
			.region(Region.of(region))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ak, sk)))
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build();
	}
}
