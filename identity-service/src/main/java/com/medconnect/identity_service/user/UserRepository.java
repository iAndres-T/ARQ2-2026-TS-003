package com.medconnect.identity_service.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserAccount, UUID> {

	Optional<UserAccount> findByEmail(String email);
}
