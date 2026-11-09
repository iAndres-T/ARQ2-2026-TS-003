package com.medconnect.recording_service.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RecordingRepository extends JpaRepository<Recording, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Recording r where r.id = :id")
	Optional<Recording> findForUpdate(@Param("id") UUID id);

	Optional<Recording> findByProviderRecordingId(String providerRecordingId);

	Optional<Recording> findFirstByConsultationIdOrderByCreatedAtDesc(UUID consultationId);
}
