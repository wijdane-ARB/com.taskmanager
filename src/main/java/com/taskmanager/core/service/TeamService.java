package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.CreateTeamRequest;
import com.taskmanager.core.dto.request.TeamInviteRequest;
import com.taskmanager.core.dto.response.TeamInviteResponse;
import com.taskmanager.core.dto.response.TeamMemberResponse;
import com.taskmanager.core.dto.response.TeamResponse;

import java.util.List;
import java.util.UUID;

public interface TeamService {

    TeamResponse createTeam(UUID ownerId, CreateTeamRequest request);

    List<TeamResponse> getMyTeams(UUID userId);

    TeamResponse getTeamById(UUID userId, UUID teamId);

    List<TeamMemberResponse> getMembers(UUID userId, UUID teamId);

    TeamInviteResponse inviteMember(UUID inviterId, UUID teamId, TeamInviteRequest request);

    /** Pending invites addressed to the caller's own email. */
    List<TeamInviteResponse> getMyPendingInvites(UUID userId);

    TeamResponse acceptInvite(UUID userId, UUID inviteId);

    void declineInvite(UUID userId, UUID inviteId);

    /** Only the team owner can remove members; owner cannot remove themself. */
    void removeMember(UUID callerId, UUID teamId, UUID memberUserId);

    /**
     * Central access check — every project/task/chat access path will
     * eventually call this instead of a per-resource owner check.
     */
    boolean hasTeamAccess(UUID teamId, UUID callerId);
}