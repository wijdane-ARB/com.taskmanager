package com.taskmanager.core.dto.response;

import java.time.Instant;
import java.util.UUID;

public record TeamMemberResponse(
        UUID userId,
        String displayName,
        String email,
        boolean isOwner,
        Instant joinedAt
) {}