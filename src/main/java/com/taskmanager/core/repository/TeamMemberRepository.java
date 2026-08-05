package com.taskmanager.core.repository;

import com.taskmanager.core.entity.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

    boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);

    List<TeamMember> findByTeamIdOrderByJoinedAtAsc(UUID teamId);

    /**
     * All teams a user belongs to (as owner or invited member) —
     * this is what "my teams" resolves against, since the owner is
     * also inserted as a TeamMember row at creation time (see
     * TeamServiceImpl.createTeam()). Single source of truth for
     * membership, no separate "am I the owner" branch needed.
     */
    @Query("SELECT tm.team.id FROM TeamMember tm WHERE tm.user.id = :userId")
    List<UUID> findTeamIdsForUser(@Param("userId") UUID userId);

    void deleteByTeamIdAndUserId(UUID teamId, UUID userId);
}