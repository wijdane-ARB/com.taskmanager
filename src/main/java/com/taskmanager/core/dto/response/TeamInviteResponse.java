package com.taskmanager.core.dto.response;

import com.taskmanager.core.entity.TeamInvite;
import com.taskmanager.core.enums.InviteStatus;

import java.time.Instant;
import java.util.UUID;

public record TeamInviteResponse(
        UUID id,
        UUID teamId,
        String teamName,
        String invitedEmail,
        String invitedByDisplayName,
        InviteStatus status,
        Instant createdAt
) {
    public static TeamInviteResponse from(TeamInvite invite) {
        return new TeamInviteResponse(
                invite.getId(),
                invite.getTeam().getId(),
                invite.getTeam().getName(),
                invite.getInvitedEmail(),
                invite.getInvitedBy().getDisplayName(),
                invite.getStatus(),
                invite.getCreatedAt()
        );
    }
}