package com.taskmanager.core.dto.response;

import com.taskmanager.core.entity.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound DTO for the authenticated user's own profile.
 *
 * NEVER return the User entity directly — it carries passwordHash.
 * This DTO is the firewall between the persistence model and the API contract.
 *
 * Returned by:
 *   GET  /api/v1/users/me
 *   PATCH /api/v1/users/me
 */
public record UserResponse(

        UUID id,
        String displayName,
        String email,
        boolean isProSubscriber,
        Instant createdAt,
        Instant updatedAt

) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.isProSubscriber(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
