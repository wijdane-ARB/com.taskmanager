package com.taskmanager.core.repository;

import com.taskmanager.core.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);
    long countByRecipientIdAndReadFalse(UUID recipientId);

    /** Ownership is part of the lookup, so "not yours" and "doesn't exist" are indistinguishable. */
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipient.id = :userId AND n.read = false")
    int markAllReadForUser(@Param("userId") UUID userId);
}