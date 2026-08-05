package com.taskmanager.core.repository;

import com.taskmanager.core.entity.Task;
import com.taskmanager.core.enums.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID>,
        JpaSpecificationExecutor<Task> {

    /**
     * Team-wide project task list — any team member viewing a project sees
     * every task in it, not just their own. Replaces the old
     * owner-scoped findByProjectIdAndOwnerIdOrderByDueDateAscPriorityLevelDesc.
     */
    @EntityGraph(attributePaths = {"project"})
    Page<Task> findByProjectIdOrderByDueDateAscPriorityLevelDesc(UUID projectId, Pageable pageable);

    /**
     * Single-task lookup for the read-access path. Team is reached via
     * project.team in TaskServiceImpl, so we eagerly fetch both here to
     * avoid a second round trip / N+1 when the service checks team access.
     * Replaces the old owner-scoped findByIdAndOwnerIdAndStatusNot.
     */
    @EntityGraph(attributePaths = {"project", "project.team"})
    Optional<Task> findByIdAndStatusNot(UUID taskId, TaskStatus status);

    /**
     * "Today"/"Overdue" stay personal (owner-scoped) — see
     * TaskServiceImpl's class Javadoc for why these weren't made team-wide.
     *
     * FIX: previously combined @EntityGraph(attributePaths = {"project"})
     * with a custom @Query returning Page<Task>. Spring Data has to
     * auto-derive a COUNT query from the JPQL and reconcile it against the
     * entity graph hint for pagination — this combination is unreliable
     * (throws at query-derivation time on some Hibernate/Spring Data Boot
     * 4.x combos), which is what was producing the 500s on /tasks/today
     * and /tasks/overdue. The other paginated queries in this interface
     * either use @EntityGraph with a *derived* (non-@Query) method, or use
     * a Specification — neither of which hit this issue.
     *
     * Fix: drop @EntityGraph here and fetch the project explicitly via
     * "LEFT JOIN FETCH t.project" inside the JPQL instead. This avoids the
     * count-query/entity-graph interaction entirely while still avoiding
     * N+1 when the response DTO calls task.getProject().
     */
    @Query("""
        SELECT t FROM Task t
        LEFT JOIN FETCH t.project
        WHERE t.owner.id = :ownerId
          AND t.dueDate < :now
          AND t.status NOT IN (
              com.taskmanager.core.enums.TaskStatus.COMPLETED,
              com.taskmanager.core.enums.TaskStatus.CANCELLED
          )
        ORDER BY t.dueDate ASC
        """)
    Page<Task> findOverdueTasksForUser(@Param("ownerId") UUID ownerId,
                                       @Param("now")     Instant now,
                                       Pageable pageable);

    @Query("""
        SELECT t FROM Task t
        LEFT JOIN FETCH t.project
        WHERE t.owner.id  = :ownerId
          AND t.dueDate  >= :startOfDay
          AND t.dueDate  <  :endOfDay
          AND t.status NOT IN (
              com.taskmanager.core.enums.TaskStatus.COMPLETED,
              com.taskmanager.core.enums.TaskStatus.CANCELLED
          )
        ORDER BY t.priorityLevel DESC, t.dueDate ASC
        """)
    Page<Task> findTodaysTasksForUser(@Param("ownerId")    UUID ownerId,
                                      @Param("startOfDay") Instant startOfDay,
                                      @Param("endOfDay")   Instant endOfDay,
                                      Pageable pageable);

    @EntityGraph(attributePaths = {"project"})
    @Query("""
        SELECT t FROM Task t
        WHERE t.owner.id    = :ownerId
          AND t.status      = com.taskmanager.core.enums.TaskStatus.COMPLETED
          AND t.completedAt >= :since
        ORDER BY t.completedAt DESC
        """)
    List<Task> findCompletedTasksForRLTraining(@Param("ownerId") UUID ownerId,
                                               @Param("since")   Instant since);

    long countByProjectIdAndStatusNotIn(UUID projectId, List<TaskStatus> excludedStatuses);
}