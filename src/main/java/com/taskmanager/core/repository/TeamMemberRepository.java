package com.taskmanager.core.repository;

import com.taskmanager.core.entity.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

    boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"user"})
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

    @Query("SELECT tm.team.id, COUNT(tm.id) FROM TeamMember tm WHERE tm.team.id IN :teamIds GROUP BY tm.team.id")
    List<Object[]> countMembersForTeamsRaw(@Param("teamIds") List<UUID> teamIds);

    default Map<UUID, Long> countMembersForTeams(List<UUID> teamIds) {
        return countMembersForTeamsRaw(teamIds).stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> (Long) row[1]
                ));
    }

    long countByTeamId(UUID teamId);

    void deleteByTeamIdAndUserId(UUID teamId, UUID userId);
}
