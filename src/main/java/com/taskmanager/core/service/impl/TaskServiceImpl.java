package com.taskmanager.core.service.impl;

import com.taskmanager.core.dto.request.CreateTaskRequest;
import com.taskmanager.core.dto.request.TaskFilterRequest;
import com.taskmanager.core.dto.request.UpdateTaskRequest;
import com.taskmanager.core.dto.response.TaskResponse;
import com.taskmanager.core.entity.Project;
import com.taskmanager.core.entity.Task;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.enums.TaskStatus;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.exception.UnauthorizedAccessException;
import com.taskmanager.core.repository.ProjectRepository;
import com.taskmanager.core.repository.TaskRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.repository.spec.TaskSpecification;
import com.taskmanager.core.service.TaskService;
import com.taskmanager.core.service.TeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Access model: reading a task requires membership on its project's team
 * (any teammate can see any task in a shared project). Writing to a task
 * (edit/complete/reopen/snooze/delete) additionally requires being the
 * task's original creator (Task.owner) — teammates can view but not touch
 * each other's tasks. "Today" and "Overdue" stay personal (owner-scoped),
 * matching the home-screen-widget framing rather than a team activity feed.
 */
@Service
@Transactional(readOnly = true)
public class TaskServiceImpl implements TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);

    private final TaskRepository    taskRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository    userRepository;
    private final TeamService       teamService;

    public TaskServiceImpl(TaskRepository taskRepository,
                           ProjectRepository projectRepository,
                           UserRepository userRepository,
                           TeamService teamService) {
        this.taskRepository    = taskRepository;
        this.projectRepository = projectRepository;
        this.userRepository    = userRepository;
        this.teamService       = teamService;
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TaskResponse createTask(UUID callerId, CreateTaskRequest request) {
        log.debug("Creating task for user {} in project {}", callerId, request.projectId());

        User creator = findUserOrThrow(callerId);
        Project project = findProjectWithAccessOrThrow(request.projectId(), callerId);

        Task task = new Task(request.title(), request.description(), creator, project);

        if (request.dueDate() != null)                  task.setDueDate(request.dueDate());
        if (request.scheduledTime() != null)            task.setScheduledTime(request.scheduledTime());
        if (request.priorityLevel() != null)            task.setPriorityLevel(request.priorityLevel());
        if (request.estimatedDurationMinutes() != null) task.setEstimatedDurationMinutes(request.estimatedDurationMinutes());
        if (request.isRecurring()) {
            task.setRecurring(true);
            task.setRecurrenceRule(request.recurrenceRule());
        }

        Task saved = taskRepository.saveAndFlush(task);
        log.info("Task {} created by user {} in project {}", saved.getId(), callerId, project.getId());

        return TaskResponse.from(saved);
    }

    // ── Read ──────────────────────────────────────────────────────────────

    /**
     * Team-wide by design: any member of a project's team can see every
     * task in it, not just tasks they personally created.
     */
    @Override
    public Page<TaskResponse> getTasks(UUID callerId, TaskFilterRequest filter, Pageable pageable) {
        if (filter.projectId() != null) {
            // Validates access and gives the same 404 whether the project
            // is missing or just not the caller's to see.
            findProjectWithAccessOrThrow(filter.projectId(), callerId);
        }

        List<UUID> teamIds = teamService.getMyTeams(callerId).stream()
                .map(t -> t.id())
                .toList();
        if (teamIds.isEmpty()) return Page.empty(pageable);

        Specification<Task> spec = TaskSpecification.of(teamIds, filter);
        return taskRepository.findAll(spec, pageable).map(TaskResponse::from);
    }

    @Override
    public TaskResponse getTaskById(UUID callerId, UUID taskId) {
        return TaskResponse.from(findTaskForReadOrThrow(callerId, taskId));
    }

    @Override
    public Page<TaskResponse> getTasksByProject(UUID callerId, UUID projectId, Pageable pageable) {
        findProjectWithAccessOrThrow(projectId, callerId);

        return taskRepository
                .findByProjectIdOrderByDueDateAscPriorityLevelDesc(projectId, pageable)
                .map(TaskResponse::from);
    }

    @Override
    public Page<TaskResponse> getTodaysTasks(UUID callerId, String timeZoneId, Pageable pageable) {
        ZoneId zoneId;
        try {
            zoneId = (timeZoneId != null && !timeZoneId.isBlank())
                    ? ZoneId.of(timeZoneId)
                    : ZoneOffset.UTC;
        } catch (Exception e) {
            zoneId = ZoneOffset.UTC;
        }

        ZonedDateTime nowInUserZone = ZonedDateTime.now(zoneId);
        Instant startOfDay = nowInUserZone.toLocalDate().atStartOfDay(zoneId).toInstant();
        Instant endOfDay   = startOfDay.plusSeconds(86_400);

        return taskRepository
                .findTodaysTasksForUser(callerId, startOfDay, endOfDay, pageable)
                .map(TaskResponse::from);
    }

    @Override
    public Page<TaskResponse> getOverdueTasks(UUID callerId, Pageable pageable) {
        return taskRepository
                .findOverdueTasksForUser(callerId, Instant.now(), pageable)
                .map(TaskResponse::from);
    }

    // ── Mutations — creator-only ────────────────────────────────────────

    @Override
    @Transactional
    public TaskResponse updateTask(UUID callerId, UUID taskId, UpdateTaskRequest request) {
        log.debug("Updating task {} for user {}", taskId, callerId);

        Task task = findTaskForWriteOrThrow(callerId, taskId);

        if (request.title() != null)                    task.setTitle(request.title());
        if (request.description() != null)              task.setDescription(request.description());
        if (request.dueDate() != null)                  task.setDueDate(request.dueDate());
        if (request.scheduledTime() != null)            task.setScheduledTime(request.scheduledTime());
        if (request.priorityLevel() != null)            task.setPriorityLevel(request.priorityLevel());
        if (request.estimatedDurationMinutes() != null) task.setEstimatedDurationMinutes(request.estimatedDurationMinutes());
        if (request.isRecurring() != null)              task.setRecurring(request.isRecurring());
        if (request.recurrenceRule() != null)           task.setRecurrenceRule(request.recurrenceRule());
        if (request.status() != null)                   applyStatusTransition(task, request.status());

        return TaskResponse.from(taskRepository.save(task));
    }

    @Override
    @Transactional
    public TaskResponse completeTask(UUID callerId, UUID taskId) {
        Task task = findTaskForWriteOrThrow(callerId, taskId);
        if (task.getStatus() == TaskStatus.COMPLETED) return TaskResponse.from(task);

        task.markAsCompleted();
        Task saved = taskRepository.save(task);
        log.info("Task {} completed at {} for user {}", saved.getId(), saved.getCompletedAt(), callerId);
        return TaskResponse.from(saved);
    }

    @Override
    @Transactional
    public TaskResponse reopenTask(UUID callerId, UUID taskId) {
        Task task = findTaskForWriteOrThrow(callerId, taskId);
        if (task.getStatus() != TaskStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Only COMPLETED tasks can be reopened. Current status: " + task.getStatus());
        }
        task.markAsReopened();
        Task saved = taskRepository.save(task);
        log.info("Task {} reopened (total reopens: {}) for user {}", saved.getId(), saved.getReopenedCount(), callerId);
        return TaskResponse.from(saved);
    }

    @Override
    @Transactional
    public TaskResponse recordSnooze(UUID callerId, UUID taskId) {
        Task task = findTaskForWriteOrThrow(callerId, taskId);
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Cannot record a snooze on a task with status: " + task.getStatus()
                            + ". Snooze events are only valid for active tasks.");
        }
        task.recordSnooze();
        return TaskResponse.from(taskRepository.save(task));
    }

    @Override
    @Transactional
    public TaskResponse deleteTask(UUID callerId, UUID taskId) {
        Task task = findTaskForWriteOrThrow(callerId, taskId);
        task.setStatus(TaskStatus.CANCELLED);
        Task saved = taskRepository.save(task);
        log.info("Task {} soft-deleted (CANCELLED) by user {}", taskId, callerId);
        return TaskResponse.from(saved);
    }

    // ── Private Helpers ──────────────────────────────────────────────────

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    /**
     * Single lookup collapsing "project doesn't exist" and "exists but
     * you're not on its team" into the same 404 — same enumeration
     * hardening as ProjectServiceImpl.findProjectOrThrow().
     */
    private Project findProjectWithAccessOrThrow(UUID projectId, UUID callerId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found."));

        if (!teamService.hasTeamAccess(project.getTeam().getId(), callerId)) {
            throw new ResourceNotFoundException("Project not found.");
        }

        return project;
    }

    /**
     * Read access: any member of the task's project's team. Collapses
     * "task doesn't exist" and "exists but you're not on its team" into
     * the same 404, same pattern as findProjectWithAccessOrThrow().
     */
    private Task findTaskForReadOrThrow(UUID callerId, UUID taskId) {
        Task task = taskRepository.findByIdAndStatusNot(taskId, TaskStatus.CANCELLED)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));

        if (!teamService.hasTeamAccess(task.getProject().getTeam().getId(), callerId)) {
            throw new ResourceNotFoundException("Task not found.");
        }

        return task;
    }

    /**
     * Write access: team membership (via findTaskForReadOrThrow) AND being
     * the task's original creator. This layer uses 403, not 404 — unlike
     * the not-found/not-a-member collapse above, "I can see this task but
     * can't edit it" is a legitimate, non-enumerating distinction to
     * surface, since the caller already knows the task exists.
     */
    private Task findTaskForWriteOrThrow(UUID callerId, UUID taskId) {
        Task task = findTaskForReadOrThrow(callerId, taskId);
        if (!task.getOwner().getId().equals(callerId)) {
            throw new UnauthorizedAccessException(
                    "Only the person who created this task can edit, complete, or delete it.");
        }
        return task;
    }

    private void applyStatusTransition(Task task, TaskStatus targetStatus) {
        switch (targetStatus) {
            case IN_PROGRESS -> task.markAsStarted();
            case COMPLETED   -> task.markAsCompleted();
            case REOPENED    -> {
                if (task.getStatus() != TaskStatus.COMPLETED) {
                    throw new IllegalStateException(
                            "Only COMPLETED tasks can be reopened. Current status: " + task.getStatus());
                }
                task.markAsReopened();
            }
            case CANCELLED -> {
                if (task.getStatus() == TaskStatus.COMPLETED) {
                    throw new IllegalStateException("Cannot cancel a completed task. Reopen it first.");
                }
                task.setStatus(TaskStatus.CANCELLED);
            }
            case PENDING -> throw new IllegalStateException(
                    "Cannot transition directly to PENDING. Use /reopen to re-activate a completed task.");
            default -> throw new IllegalStateException(
                    "Unsupported status transition: " + task.getStatus() + " → " + targetStatus);
        }
    }
}