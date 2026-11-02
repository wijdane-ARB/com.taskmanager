package com.taskmanager.core.repository;

import com.taskmanager.core.entity.Project;
import com.taskmanager.core.enums.ProjectStatus;
import com.taskmanager.core.enums.TaskStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    @EntityGraph(attributePaths = {"createdBy", "team"})
    List<Project> findByTeamIdAndStatusOrderByCreatedAtDesc(UUID teamId, ProjectStatus status);

    @Override
    @NonNull
    @EntityGraph(attributePaths = {"createdBy", "team"})
    Optional<Project> findById(@NonNull UUID id);

    long countByTeamIdAndStatus(UUID teamId, ProjectStatus status);

    /**
     * Active task counts for ALL of a team's projects in a single query —
     * same N+1 fix as before, rescoped from owner to team.
     */
    @Query("""
        SELECT t.project.id, COUNT(t)
        FROM Task t
        WHERE t.project.team.id = :teamId
          AND t.status NOT IN :excludedStatuses
        GROUP BY t.project.id
        """)
    List<Object[]> countActiveTasksPerProjectForTeam(
            @Param("teamId") UUID teamId,
            @Param("excludedStatuses") List<TaskStatus> excludedStatuses
    );

    default Map<UUID, Long> activeTaskCountMapForTeam(UUID teamId,
                                                      List<TaskStatus> excludedStatuses) {
        return countActiveTasksPerProjectForTeam(teamId, excludedStatuses)
                .stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }
}
