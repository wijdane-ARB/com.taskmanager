package com.taskmanager.core.dto.response;

import com.taskmanager.core.entity.Team;

import java.time.Instant;
import java.util.UUID;

public record TeamResponse(
        UUID id,
        String name,
        UUID ownerId,
        String ownerDisplayName,
        long memberCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static TeamResponse from(Team team, long memberCount) {
        return new TeamResponse(
                team.getId(),
                team.getName(),
                team.getOwner().getId(),
                team.getOwner().getDisplayName(),
                memberCount,
                team.getCreatedAt(),
                team.getUpdatedAt()
        );
    }
}