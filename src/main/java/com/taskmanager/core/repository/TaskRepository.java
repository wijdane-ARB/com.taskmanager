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
        JpaSpecificationExecutor<Task> {     // ← required for getTasks() Specification support

    @EntityGraph(attributePaths = {"project"})
    Page<Task> findByProjectIdAndOwnerIdOrderByDueDateAscPriorityLevelDesc(
            UUID projectId, UUID ownerId, Pageable pageable
    );

    Optional<Task> findByIdAndOwnerId(UUID taskId, UUID ownerId);

    Optional<Task> findByIdAndOwnerIdAndStatusNot(UUID taskId, UUID ownerId, TaskStatus status);

    /**
     * Overdue tasks — now paginated.
     * The List variant was removed; callers pass a Pageable and receive a Page.
     * The JPQL is unchanged apart from the return type and added Pageable param.
     */
    @Query("""
        SELECT t FROM Task t
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

    /**
     * Today's tasks — now paginated.
     */
    @Query("""
        SELECT t FROM Task t
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