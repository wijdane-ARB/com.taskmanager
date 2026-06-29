package com.taskmanager.core.repository;

import com.taskmanager.core.entity.Project;
import com.taskmanager.core.enums.ProjectStatus;
import com.taskmanager.core.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByOwnerIdAndStatusOrderByCreatedAtDesc(UUID ownerId, ProjectStatus status);

    Optional<Project> findByIdAndOwnerId(UUID projectId, UUID ownerId);

    long countByOwnerIdAndStatus(UUID ownerId, ProjectStatus status);

    /**
     * Returns active task counts for ALL of an owner's projects in a single query.
     *
     * Problem solved: the old getProjects() issued one COUNT query per project
     * (N+1 pattern). With 5 active projects that was 6 DB round-trips; with 50
     * archived projects it was 51.
     *
     * This query does it in one shot and returns Object[]{projectId, count} pairs.
     * The service converts the list to a Map<UUID, Long> for O(1) lookup per project.
     *
     * "Active" means any status that is NOT in the terminal set
     * (COMPLETED, CANCELLED) — mirrors the definition in TaskRepository and
     * ProjectServiceImpl.TERMINAL_STATUSES.
     */
    @Query("""
        SELECT t.project.id, COUNT(t)
        FROM Task t
        WHERE t.project.owner.id = :ownerId
          AND t.status NOT IN :excludedStatuses
        GROUP BY t.project.id
        """)
    List<Object[]> countActiveTasksPerProjectForOwner(
            @Param("ownerId") UUID ownerId,
            @Param("excludedStatuses") List<TaskStatus> excludedStatuses
    );

    /**
     * Convenience helper: converts the raw Object[][] result into a typed map.
     * Call this from the service instead of the raw query method.
     *
     * Default implementation lives here (interface default) so callers don't
     * need a utility class — the repository is the natural home for this mapping.
     */
    default Map<UUID, Long> activeTaskCountMapForOwner(UUID ownerId,
                                                       List<TaskStatus> excludedStatuses) {
        return countActiveTasksPerProjectForOwner(ownerId, excludedStatuses)
                .stream()
                .collect(Collectors.toMap(
                        row -> (UUID)  row[0],
                        row -> (Long)  row[1]
                ));
    }
}