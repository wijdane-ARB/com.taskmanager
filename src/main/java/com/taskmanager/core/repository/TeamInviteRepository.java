package com.taskmanager.core.repository;

import com.taskmanager.core.entity.TeamInvite;
import com.taskmanager.core.enums.InviteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TeamInviteRepository extends JpaRepository<TeamInvite, UUID> {

    List<TeamInvite> findByInvitedEmailIgnoreCaseAndStatus(String invitedEmail, InviteStatus status);

    Optional<TeamInvite> findByTeamIdAndInvitedEmailIgnoreCaseAndStatus(
            UUID teamId, String invitedEmail, InviteStatus status);
}