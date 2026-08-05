// FILE: VerificationCodeRepository.java
package com.taskmanager.core.repository;

import com.taskmanager.core.entity.VerificationCode;
import com.taskmanager.core.enums.VerificationCodeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {

    Optional<VerificationCode> findTopByUserIdAndTypeAndUsedFalseOrderByCreatedAtDesc(
            UUID userId, VerificationCodeType type
    );

    // Invalidates every outstanding code of this type for the user so that
    // only the most recently issued one can ever be redeemed.
    @Modifying
    @Query("UPDATE VerificationCode vc SET vc.used = true " +
            "WHERE vc.user.id = :userId AND vc.type = :type AND vc.used = false")
    void invalidateAllForUser(@Param("userId") UUID userId, @Param("type") VerificationCodeType type);

    // Nightly cleanup — same pattern as RefreshTokenRepository.
    @Modifying
    @Query("DELETE FROM VerificationCode vc WHERE vc.expiresAt < :now")
    void deleteAllExpiredBefore(@Param("now") Instant now);
}