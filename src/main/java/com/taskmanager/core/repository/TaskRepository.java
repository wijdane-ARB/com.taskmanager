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
     * Team-wide project task list.
     *
     * FIX: the old findByProjectIdOrderByDueDateAscPriorityLevelDesc had no
     * status filter, so soft-deleted (CANCELLED) tasks kept showing up in
     * project lists. Callers pass TaskStatus.CANCELLED as the excluded status.
     */
    @EntityGraph(attributePaths = {"project"})
    Page<Task> findByProjectIdAndStatusNotOrderByDueDateAscPriorityLevelDesc(
            UUID projectId, TaskStatus excludedStatus, Pageable pageable);

    @EntityGraph(attributePaths = {"project", "project.team", "attachments"})
    Optional<Task> findByIdAndStatusNot(UUID taskId, TaskStatus status);

    @Query(value = """
        SELECT t FROM Task t
        LEFT JOIN FETCH t.project
        WHERE t.assignee.id = :assigneeId
          AND t.project.team.id IN :teamIds
          AND t.dueDate < :now
          AND t.status NOT IN (
              com.taskmanager.core.enums.TaskStatus.COMPLETED,
              com.taskmanager.core.enums.TaskStatus.CANCELLED
          )
        ORDER BY t.dueDate ASC
        """, countQuery = """
        SELECT COUNT(t) FROM Task t
        WHERE t.assignee.id = :assigneeId
          AND t.project.team.id IN :teamIds
          AND t.dueDate < :now
          AND t.status NOT IN (
              com.taskmanager.core.enums.TaskStatus.COMPLETED,
              com.taskmanager.core.enums.TaskStatus.CANCELLED
          )
        """)
    Page<Task> findOverdueTasksForUser(@Param("assigneeId") UUID assigneeId,
                                       @Param("teamIds")    List<UUID> teamIds,
                                       @Param("now")        Instant now,
                                       Pageable pageable);

    @Query(value = """
        SELECT t FROM Task t
        LEFT JOIN FETCH t.project
        WHERE t.assignee.id = :assigneeId
          AND t.project.team.id IN :teamIds
          AND t.dueDate  >= :startOfDay
          AND t.dueDate  <  :endOfDay
          AND t.status NOT IN (
              com.taskmanager.core.enums.TaskStatus.COMPLETED,
              com.taskmanager.core.enums.TaskStatus.CANCELLED
          )
        ORDER BY t.priorityLevel DESC, t.dueDate ASC
        """, countQuery = """
        SELECT COUNT(t) FROM Task t
        WHERE t.assignee.id = :assigneeId
          AND t.project.team.id IN :teamIds
          AND t.dueDate  >= :startOfDay
          AND t.dueDate  <  :endOfDay
          AND t.status NOT IN (
              com.taskmanager.core.enums.TaskStatus.COMPLETED,
              com.taskmanager.core.enums.TaskStatus.CANCELLED
          )
        """)
    Page<Task> findTodaysTasksForUser(@Param("assigneeId")  UUID assigneeId,
                                      @Param("teamIds")     List<UUID> teamIds,
                                      @Param("startOfDay")  Instant startOfDay,
                                      @Param("endOfDay")    Instant endOfDay,
                                      Pageable pageable);

    @Query("""
        SELECT t FROM Task t
        LEFT JOIN FETCH t.project
        WHERE t.assignee.id = :userId
          AND t.status      = com.taskmanager.core.enums.TaskStatus.COMPLETED
          AND t.completedAt >= :since
        ORDER BY t.completedAt DESC
        """)
    List<Task> findCompletedTasksForRLTraining(@Param("userId")     UUID userId,
                                               @Param("since")      Instant since);

    @Query("""
        SELECT COUNT(t) FROM Task t
        WHERE t.assignee.id = :userId
          AND t.project.team.id IN :teamIds
          AND t.status IN :statuses
        """)
    long countTasksForUserInTeamsByStatus(@Param("userId") UUID userId, @Param("teamIds") List<UUID> teamIds, @Param("statuses") List<TaskStatus> statuses);

    @Query("""
        SELECT COUNT(t) FROM Task t
        WHERE t.assignee.id = :userId
          AND t.project.team.id IN :teamIds
          AND t.dueDate < :now
          AND t.status NOT IN :excludedStatuses
        """)
    long countOverdueTasksForUserInTeams(@Param("userId") UUID userId, @Param("teamIds") List<UUID> teamIds, @Param("now") Instant now, @Param("excludedStatuses") List<TaskStatus> excludedStatuses);

    long countByProjectIdAndStatusNotIn(UUID projectId, List<TaskStatus> excludedStatuses);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM Task t WHERE t.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") UUID projectId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Task t SET t.assignee = null WHERE t.assignee.id = :userId AND t.project.team.id = :teamId")
    void unassignUserFromTeamTasks(@Param("userId") UUID userId, @Param("teamId") UUID teamId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Task t SET t.status = com.taskmanager.core.enums.TaskStatus.CANCELLED WHERE t.recurrenceGroupId = :groupId AND t.status = com.taskmanager.core.enums.TaskStatus.PENDING AND t.id != :excludeId")
    void cancelFutureOccurrences(@Param("groupId") UUID groupId, @Param("excludeId") UUID excludeId);

    @Query("""
        SELECT t FROM Task t
        LEFT JOIN FETCH t.project
        WHERE t.assignee.id = :userId
          AND t.project.team.id = :teamId
          AND t.status NOT IN (com.taskmanager.core.enums.TaskStatus.CANCELLED)
          AND (
            (t.scheduledTime IS NOT NULL AND t.scheduledTime >= :start AND t.scheduledTime < :end)
            OR
            (t.scheduledTime IS NULL AND t.dueDate IS NOT NULL AND t.dueDate >= :start AND t.dueDate < :end)
          )
        ORDER BY t.scheduledTime ASC, t.dueDate ASC
        """)
    List<Task> findCalendarTasksForUser(@Param("userId") UUID userId,
                                        @Param("teamId") UUID teamId,
                                        @Param("start") Instant start,
                                        @Param("end") Instant end,
                                        Pageable pageable);

    @Query("""
        SELECT t FROM Task t
        LEFT JOIN FETCH t.project
        WHERE t.project.team.id = :teamId
          AND t.status NOT IN (com.taskmanager.core.enums.TaskStatus.CANCELLED)
          AND (
            (t.scheduledTime IS NOT NULL AND t.scheduledTime >= :start AND t.scheduledTime < :end)
            OR
            (t.scheduledTime IS NULL AND t.dueDate IS NOT NULL AND t.dueDate >= :start AND t.dueDate < :end)
          )
        ORDER BY t.scheduledTime ASC, t.dueDate ASC
        """)
    List<Task> findCalendarTasksForTeam(@Param("teamId") UUID teamId,
                                        @Param("start") Instant start,
                                        @Param("end") Instant end,
                                        Pageable pageable);
}