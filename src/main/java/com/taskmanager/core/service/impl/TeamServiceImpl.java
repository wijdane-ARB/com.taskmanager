package com.taskmanager.core.service.impl;

import com.taskmanager.core.dto.request.CreateTeamRequest;
import com.taskmanager.core.dto.request.TeamInviteRequest;
import com.taskmanager.core.dto.response.TeamInviteResponse;
import com.taskmanager.core.dto.response.TeamMemberResponse;
import com.taskmanager.core.dto.response.TeamResponse;
import com.taskmanager.core.entity.Team;
import com.taskmanager.core.entity.TeamInvite;
import com.taskmanager.core.entity.TeamMember;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.enums.InviteStatus;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.exception.UnauthorizedAccessException;
import com.taskmanager.core.repository.TeamInviteRepository;
import com.taskmanager.core.repository.TeamMemberRepository;
import com.taskmanager.core.repository.TeamRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.service.TeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Access model: a user has access to a team iff they have a TeamMember row
 * for it (the owner is always inserted as a TeamMember at creation time —
 * see createTeam() — so there is exactly one membership check anywhere in
 * this class: hasTeamAccess()).
 *
 * Enumeration hardening: every read/mutate path that takes a teamId goes
 * through findTeamWithAccessOrThrow(), which collapses "team doesn't
 * exist" and "team exists but you're not a member" into the same 404.
 * Same principle as ProjectServiceImpl.findProjectOrThrow() and
 * validateInviteBelongsToCaller() below — never let an authenticated user
 * distinguish "not found" from "not yours" via status code or message.
 */
@Service
@Transactional(readOnly = true)
public class TeamServiceImpl implements TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamServiceImpl.class);

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamInviteRepository teamInviteRepository;
    private final UserRepository userRepository;

    public TeamServiceImpl(
            TeamRepository teamRepository,
            TeamMemberRepository teamMemberRepository,
            TeamInviteRepository teamInviteRepository,
            UserRepository userRepository
    ) {
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamInviteRepository = teamInviteRepository;
        this.userRepository = userRepository;
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TeamResponse createTeam(UUID ownerId, CreateTeamRequest request) {
        User owner = findUserOrThrow(ownerId);

        Team team = new Team(request.name(), owner);
        Team saved = teamRepository.saveAndFlush(team);

        // Owner is also a TeamMember row — single source of truth for
        // membership queries, no separate "is this the owner" branch
        // needed anywhere downstream.
        teamMemberRepository.save(new TeamMember(saved, owner));

        log.info("Team {} created by user {}", saved.getId(), ownerId);
        return TeamResponse.from(saved, 1L);
    }

    // ── Read ──────────────────────────────────────────────────────────────

    @Override
    public List<TeamResponse> getMyTeams(UUID userId) {
        List<UUID> teamIds = teamMemberRepository.findTeamIdsForUser(userId);
        if (teamIds.isEmpty()) return List.of();

        return teamRepository.findAllById(teamIds).stream()
                .map(t -> TeamResponse.from(t, teamMemberRepository.findByTeamIdOrderByJoinedAtAsc(t.getId()).size()))
                .toList();
    }

    @Override
    public TeamResponse getTeamById(UUID userId, UUID teamId) {
        Team team = findTeamWithAccessOrThrow(teamId, userId);
        long memberCount = teamMemberRepository.findByTeamIdOrderByJoinedAtAsc(teamId).size();
        return TeamResponse.from(team, memberCount);
    }

    @Override
    public List<TeamMemberResponse> getMembers(UUID userId, UUID teamId) {
        Team team = findTeamWithAccessOrThrow(teamId, userId);

        return teamMemberRepository.findByTeamIdOrderByJoinedAtAsc(teamId).stream()
                .map(tm -> new TeamMemberResponse(
                        tm.getUser().getId(),
                        tm.getUser().getDisplayName(),
                        tm.getUser().getEmail(),
                        tm.getUser().getId().equals(team.getOwner().getId()),
                        tm.getJoinedAt()
                ))
                .toList();
    }

    // ── Invites ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TeamInviteResponse inviteMember(UUID inviterId, UUID teamId, TeamInviteRequest request) {
        Team team = findTeamWithAccessOrThrow(teamId, inviterId);
        User inviter = findUserOrThrow(inviterId);

        // No-op guard: already a member?
        userRepository.findByEmail(request.email()).ifPresent(existing -> {
            if (teamMemberRepository.existsByTeamIdAndUserId(teamId, existing.getId())) {
                throw new IllegalStateException("This user is already a member of the team.");
            }
        });

        // No-op guard: pending invite already outstanding for this email?
        teamInviteRepository
                .findByTeamIdAndInvitedEmailIgnoreCaseAndStatus(teamId, request.email(), InviteStatus.PENDING)
                .ifPresent(existing -> {
                    throw new IllegalStateException("An invite is already pending for this email.");
                });

        TeamInvite invite = new TeamInvite(team, request.email(), inviter);
        TeamInvite saved = teamInviteRepository.saveAndFlush(invite);

        log.info("Team {} invite sent to {} by user {}", teamId, request.email(), inviterId);
        // NOTE: no email is actually sent yet — wire into EmailService in
        // a follow-up pass (sendTeamInvite(toEmail, teamName, inviterName)).
        return TeamInviteResponse.from(saved);
    }

    @Override
    public List<TeamInviteResponse> getMyPendingInvites(UUID userId) {
        User user = findUserOrThrow(userId);
        return teamInviteRepository
                .findByInvitedEmailIgnoreCaseAndStatus(user.getEmail(), InviteStatus.PENDING)
                .stream()
                .map(TeamInviteResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public TeamResponse acceptInvite(UUID userId, UUID inviteId) {
        User user = findUserOrThrow(userId);
        TeamInvite invite = findInviteOrThrow(inviteId);

        validateInviteBelongsToCaller(invite, user);

        if (invite.getStatus() != InviteStatus.PENDING) {
            throw new IllegalStateException("This invite has already been " + invite.getStatus().name().toLowerCase() + ".");
        }

        Team team = invite.getTeam();

        if (!teamMemberRepository.existsByTeamIdAndUserId(team.getId(), userId)) {
            teamMemberRepository.save(new TeamMember(team, user));
        }

        invite.accept();
        teamInviteRepository.save(invite);

        log.info("User {} accepted invite to team {}", userId, team.getId());
        long memberCount = teamMemberRepository.findByTeamIdOrderByJoinedAtAsc(team.getId()).size();
        return TeamResponse.from(team, memberCount);
    }

    @Override
    @Transactional
    public void declineInvite(UUID userId, UUID inviteId) {
        User user = findUserOrThrow(userId);
        TeamInvite invite = findInviteOrThrow(inviteId);

        validateInviteBelongsToCaller(invite, user);

        if (invite.getStatus() != InviteStatus.PENDING) {
            throw new IllegalStateException("This invite has already been " + invite.getStatus().name().toLowerCase() + ".");
        }

        invite.decline();
        teamInviteRepository.save(invite);
        log.info("User {} declined invite {}", userId, inviteId);
    }

    // ── Membership management ────────────────────────────────────────────

    @Override
    @Transactional
    public void removeMember(UUID callerId, UUID teamId, UUID memberUserId) {
        Team team = findTeamWithAccessOrThrow(teamId, callerId); // 404 if not a member at all

        if (!team.getOwner().getId().equals(callerId)) {
            throw new UnauthorizedAccessException("Only the team owner can remove members.");
        }
        if (team.getOwner().getId().equals(memberUserId)) {
            throw new IllegalStateException("The team owner cannot be removed. Delete the team instead.");
        }

        teamMemberRepository.deleteByTeamIdAndUserId(teamId, memberUserId);
        log.info("User {} removed from team {} by owner {}", memberUserId, teamId, callerId);
    }

    // ── Access check ─────────────────────────────────────────────────────

    @Override
    public boolean hasTeamAccess(UUID teamId, UUID callerId) {
        return teamMemberRepository.existsByTeamIdAndUserId(teamId, callerId);
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private void requireAccess(UUID teamId, UUID callerId) {
        if (!hasTeamAccess(teamId, callerId)) {
            throw new UnauthorizedAccessException("You are not a member of this team.");
        }
    }

    private void validateInviteBelongsToCaller(TeamInvite invite, User caller) {
        if (!invite.getInvitedEmail().equalsIgnoreCase(caller.getEmail())) {
            // Deliberately the same message as ResourceNotFoundException would
            // give — don't reveal that an invite exists for a different email.
            throw new ResourceNotFoundException("Invite not found.");
        }
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private Team findTeamOrThrow(UUID teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found: " + teamId));
    }

    /**
     * Team lookup + membership check collapsed into a single 404 — same
     * enumeration-hardening as ProjectServiceImpl.findProjectOrThrow().
     * Use this (not findTeamOrThrow + requireAccess separately) on every
     * path where "team doesn't exist" and "you're not on it" must be
     * indistinguishable to the caller.
     */
    private Team findTeamWithAccessOrThrow(UUID teamId, UUID callerId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found."));
        if (!hasTeamAccess(teamId, callerId)) {
            throw new ResourceNotFoundException("Team not found.");
        }
        return team;
    }

    private TeamInvite findInviteOrThrow(UUID inviteId) {
        return teamInviteRepository.findById(inviteId)
                .orElseThrow(() -> new ResourceNotFoundException("Invite not found."));
    }
}