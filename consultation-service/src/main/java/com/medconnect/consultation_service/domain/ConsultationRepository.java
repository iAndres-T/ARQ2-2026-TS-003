package com.medconnect.consultation_service.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ConsultationRepository extends JpaRepository<Consultation, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Consultation c where c.id = :id")
	Optional<Consultation> findForUpdate(@Param("id") UUID id);

	@Query("select c.id from Consultation c where c.status = com.medconnect.consultation_service.domain.ConsultationStatus.ASSIGNED"
			+ " and c.joinedAt is null and c.assignedCommittedAt < :cutoff")
	List<UUID> findNoShowCandidates(@Param("cutoff") Instant cutoff);

	List<Consultation> findByStatusOrderByRequestedAt(ConsultationStatus status);

	boolean existsByPatientIdAndStatusIn(UUID patientId, Collection<ConsultationStatus> statuses);
}
