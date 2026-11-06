package com.idear.backend.user.infrastructure.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.idear.backend.user.domain.User;
import com.idear.backend.user.domain.UserPublicKey;

public interface UserPublicKeyRepository extends JpaRepository<UserPublicKey, Long> {

	List<UserPublicKey> findAllByUserAndRevokedAtIsNull(User user);

	Optional<UserPublicKey> findFirstByUserAndRevokedAtIsNullOrderByCreatedAtDesc(User user);
}
