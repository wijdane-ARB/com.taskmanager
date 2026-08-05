package com.taskmanager.core.controller;

import com.taskmanager.core.dto.request.CreateTeamRequest;
import com.taskmanager.core.dto.request.TeamInviteRequest;
import com.taskmanager.core.dto.response.ApiResponse;
import com.taskmanager.core.dto.response.TeamInviteResponse;
import com.taskmanager.core.dto.response.TeamMemberResponse;
import com.taskmanager.core.dto.response.TeamResponse;
import com.taskmanager.core.service.TeamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TeamResponse>> createTeam(
            @AuthenticationPrincipal UUID currentUserId,
            @Valid @RequestBody CreateTeamRequest request
    ) {
        TeamResponse team = teamService.createTeam(currentUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Team created", team));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TeamResponse>>> getMyTeams(
            @AuthenticationPrincipal UUID currentUserId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(teamService.getMyTeams(currentUserId)));
    }

    @GetMapping("/{teamId}")
    public ResponseEntity<ApiResponse<TeamResponse>> getTeam(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID teamId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(teamService.getTeamById(currentUserId, teamId)));
    }

    @GetMapping("/{teamId}/members")
    public ResponseEntity<ApiResponse<List<TeamMemberResponse>>> getMembers(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID teamId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(teamService.getMembers(currentUserId, teamId)));
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID teamId,
            @PathVariable UUID userId
    ) {
        teamService.removeMember(currentUserId, teamId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{teamId}/invites")
    public ResponseEntity<ApiResponse<TeamInviteResponse>> inviteMember(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID teamId,
            @Valid @RequestBody TeamInviteRequest request
    ) {
        TeamInviteResponse invite = teamService.inviteMember(currentUserId, teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Invite sent", invite));
    }

    /** GET /api/v1/teams/invites — pending invites addressed to my own email. */
    @GetMapping("/invites")
    public ResponseEntity<ApiResponse<List<TeamInviteResponse>>> getMyPendingInvites(
            @AuthenticationPrincipal UUID currentUserId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(teamService.getMyPendingInvites(currentUserId)));
    }

    @PostMapping("/invites/{inviteId}/accept")
    public ResponseEntity<ApiResponse<TeamResponse>> acceptInvite(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID inviteId
    ) {
        TeamResponse team = teamService.acceptInvite(currentUserId, inviteId);
        return ResponseEntity.ok(ApiResponse.ok("Joined team", team));
    }

    @PostMapping("/invites/{inviteId}/decline")
    public ResponseEntity<ApiResponse<Void>> declineInvite(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID inviteId
    ) {
        teamService.declineInvite(currentUserId, inviteId);
        return ResponseEntity.ok(ApiResponse.ok("Invite declined", null));
    }
}