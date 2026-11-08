package com.medconnect.triage_service.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface TriageRecordRepository extends JpaRepository<TriageRecord, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from TriageRecord r where r.consultationId = :id")
	Optional<TriageRecord> findForUpdate(@Param("id") UUID id);
}
