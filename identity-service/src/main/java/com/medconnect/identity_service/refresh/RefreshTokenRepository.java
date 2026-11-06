package com.medconnect.identity_service.refresh;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

	/** Atomic single-use claim: 1 only for the first caller presenting an unused token. */
	@Transactional
	@Modifying
	@Query("update RefreshToken t set t.used = true where t.tokenHash = :hash and t.used = false")
	int markUsed(@Param("hash") String hash);

	Optional<RefreshToken> findByTokenHash(String hash);
}
