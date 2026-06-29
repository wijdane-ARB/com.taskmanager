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
import com.taskmanager.core.repository.ProjectRepository;
import com.taskmanager.core.repository.TaskRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.repository.spec.TaskSpecification;
import com.taskmanager.core.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TaskServiceImpl implements TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);

    private final TaskRepository    taskRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository    userRepository;

    public TaskServiceImpl(TaskRepository taskRepository,
                           ProjectRepository projectRepository,
                           UserRepository userRepository) {
        this.taskRepository    = taskRepository;
        this.projectRepository = projectRepository;
        this.userRepository    = userRepository;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TaskResponse createTask(UUID ownerId, CreateTaskRequest request) {
        log.debug("Creating task for user {} in project {}", ownerId, request.projectId());

        User owner = findUserOrThrow(ownerId);

        Project project = projectRepository
                .findByIdAndOwnerId(request.projectId(), ownerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found or does not belong to the current user"
                ));

        Task task = new Task(request.title(), request.description(), owner, project);

        if (request.dueDate() != null)                  task.setDueDate(request.dueDate());
        if (request.scheduledTime() != null)            task.setScheduledTime(request.scheduledTime());
        if (request.priorityLevel() != null)            task.setPriorityLevel(request.priorityLevel());
        if (request.estimatedDurationMinutes() != null) task.setEstimatedDurationMinutes(request.estimatedDurationMinutes());
        if (request.isRecurring()) {
            task.setRecurring(true);
            task.setRecurrenceRule(request.recurrenceRule());
        }

        Task saved = taskRepository.save(task);
        log.info("Task {} created for user {}", saved.getId(), ownerId);

        return TaskResponse.from(saved);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * General-purpose filtered query — activates the TaskSpecification dead code.
     *
     * The repository must extend JpaSpecificationExecutor<Task> for
     * taskRepository.findAll(spec, pageable) to compile. See TaskRepository.
     */
    @Override
    public Page<TaskResponse> getTasks(UUID ownerId, TaskFilterRequest filter, Pageable pageable) {
        Specification<Task> spec = TaskSpecification.of(ownerId, filter);
        return taskRepository.findAll(spec, pageable).map(TaskResponse::from);
    }

    @Override
    public TaskResponse getTaskById(UUID ownerId, UUID taskId) {
        return TaskResponse.from(findTaskOrThrow(ownerId, taskId));
    }

    @Override
    public Page<TaskResponse> getTasksByProject(UUID ownerId, UUID projectId, Pageable pageable) {
        projectRepository.findByIdAndOwnerId(projectId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        return taskRepository
                .findByProjectIdAndOwnerIdOrderByDueDateAscPriorityLevelDesc(projectId, ownerId, pageable)
                .map(TaskResponse::from);
    }

    /**
     * Paginated "today" list.
     *
     * The old implementation returned List<Task> from a JPQL query, which
     * fetches every matching row into memory in one shot. Replaced with a
     * Specification-backed Page query so the DB only returns one page at a time.
     */
    @Override
    public Page<TaskResponse> getTodaysTasks(UUID ownerId, Pageable pageable) {
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        Instant startOfDay   = nowUtc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay     = startOfDay.plusSeconds(86_400);

        return taskRepository
                .findTodaysTasksForUser(ownerId, startOfDay, endOfDay, pageable)
                .map(TaskResponse::from);
    }

    /**
     * Paginated overdue list.
     *
     * Previously returned an unbounded List. A user dormant for a month
     * could have hundreds of overdue tasks; loading them all in a single
     * response is wasteful on both server memory and mobile bandwidth.
     */
    @Override
    public Page<TaskResponse> getOverdueTasks(UUID ownerId, Pageable pageable) {
        return taskRepository
                .findOverdueTasksForUser(ownerId, Instant.now(), pageable)
                .map(TaskResponse::from);
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TaskResponse updateTask(UUID ownerId, UUID taskId, UpdateTaskRequest request) {
        log.debug("Updating task {} for user {}", taskId, ownerId);

        Task task = findTaskOrThrow(ownerId, taskId);

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
    public TaskResponse completeTask(UUID ownerId, UUID taskId) {
        Task task = findTaskOrThrow(ownerId, taskId);
        if (task.getStatus() == TaskStatus.COMPLETED) return TaskResponse.from(task);

        task.markAsCompleted();
        Task saved = taskRepository.save(task);
        log.info("Task {} completed at {} for user {}", saved.getId(), saved.getCompletedAt(), ownerId);
        return TaskResponse.from(saved);
    }

    @Override
    @Transactional
    public TaskResponse reopenTask(UUID ownerId, UUID taskId) {
        Task task = findTaskOrThrow(ownerId, taskId);
        if (task.getStatus() != TaskStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Only COMPLETED tasks can be reopened. Current status: " + task.getStatus());
        }
        task.markAsReopened();
        Task saved = taskRepository.save(task);
        log.info("Task {} reopened (total reopens: {}) for user {}", saved.getId(), saved.getReopenedCount(), ownerId);
        return TaskResponse.from(saved);
    }

    @Override
    @Transactional
    public TaskResponse recordSnooze(UUID ownerId, UUID taskId) {
        Task task = findTaskOrThrow(ownerId, taskId);
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
    public TaskResponse deleteTask(UUID ownerId, UUID taskId) {
        Task task = findTaskOrThrow(ownerId, taskId);
        task.setStatus(TaskStatus.CANCELLED);
        Task saved = taskRepository.save(task);
        log.info("Task {} soft-deleted (CANCELLED) by user {}", taskId, ownerId);
        return TaskResponse.from(saved);
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private Task findTaskOrThrow(UUID ownerId, UUID taskId) {
        return taskRepository.findByIdAndOwnerIdAndStatusNot(taskId, ownerId, TaskStatus.CANCELLED)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Task not found or does not belong to the current user"));
    }

    private void applyStatusTransition(Task task, TaskStatus targetStatus) {
        switch (targetStatus) {
            case IN_PROGRESS -> task.markAsStarted();
            case COMPLETED   -> task.markAsCompleted();
            case REOPENED    -> task.markAsReopened();
            case CANCELLED   -> {
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