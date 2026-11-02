package com.taskmanager.core.service.impl;

import com.taskmanager.core.dto.request.CreateTaskRequest;
import com.taskmanager.core.dto.request.RescheduleTaskRequest;
import com.taskmanager.core.dto.request.TaskFilterRequest;
import com.taskmanager.core.dto.request.UpdateTaskRequest;
import com.taskmanager.core.dto.response.AttachmentResponse;
import com.taskmanager.core.dto.response.TaskActivityResponse;
import com.taskmanager.core.dto.response.TaskCommentResponse;
import com.taskmanager.core.dto.response.TaskResponse;
import com.taskmanager.core.entity.Project;
import com.taskmanager.core.entity.Task;
import com.taskmanager.core.entity.TaskComment;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.enums.ProjectStatus;
import com.taskmanager.core.enums.TaskStatus;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.exception.ForbiddenException;
import com.taskmanager.core.repository.ProjectRepository;
import com.taskmanager.core.repository.TaskRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.repository.spec.TaskSpecification;
import com.taskmanager.core.service.NotificationService;
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
import java.util.stream.Collectors;

/**
 * Access model: reading and writing a task requires membership on its
 * project's team. "Today" and "Overdue" are personal (assignee-scoped) but
 * filtered by the selected team.
 *
 * Changes in this version:
 *  - createTask rejects ARCHIVED / COMPLETED projects.
 *  - updateTask supports clearAssignee to unassign a task.
 *  - applyStatusTransition now actually validates transitions (see its Javadoc).
 *  - PATCH status=CANCELLED broadcasts TASK_DELETED, like DELETE does.
 *  - getTasksByProject no longer returns CANCELLED tasks.
 */
@Service
@Transactional(readOnly = true)
public class TaskServiceImpl implements TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);

    private final TaskRepository    taskRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository    userRepository;
    private final TeamService       teamService;
    private final com.taskmanager.core.repository.TeamMemberRepository teamMemberRepository;
    private final NotificationService notificationService;
    private final com.taskmanager.core.repository.TaskActivityRepository taskActivityRepository;
    private final com.taskmanager.core.repository.TaskCommentRepository taskCommentRepository;
    private final com.taskmanager.core.repository.AttachmentRepository attachmentRepository;
    private final com.taskmanager.core.service.StorageService storageService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public TaskServiceImpl(TaskRepository taskRepository,
                           ProjectRepository projectRepository,
                           UserRepository userRepository,
                           TeamService teamService,
                           com.taskmanager.core.repository.TeamMemberRepository teamMemberRepository,
                           NotificationService notificationService,
                           com.taskmanager.core.repository.AttachmentRepository attachmentRepository,
                           com.taskmanager.core.service.StorageService storageService,
                           com.taskmanager.core.repository.TaskActivityRepository taskActivityRepository,
                           com.taskmanager.core.repository.TaskCommentRepository taskCommentRepository,
                           org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.taskRepository    = taskRepository;
        this.projectRepository = projectRepository;
        this.userRepository    = userRepository;
        this.teamService       = teamService;
        this.teamMemberRepository = teamMemberRepository;
        this.notificationService = notificationService;
        this.attachmentRepository = attachmentRepository;
        this.storageService = storageService;
        this.taskActivityRepository = taskActivityRepository;
        this.taskCommentRepository = taskCommentRepository;
        this.eventPublisher = eventPublisher;
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TaskResponse createTask(UUID callerId, CreateTaskRequest request) {
        log.debug("Creating task for user {} in project {}", callerId, request.projectId());

        User creator = findUserOrThrow(callerId);
        Project project = findProjectWithAccessOrThrow(request.projectId(), callerId);

        // FIX: tasks could be created inside ARCHIVED / COMPLETED projects.
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot add tasks to a project that is " + project.getStatus().name().toLowerCase()
                            + ". Reactivate it first.");
        }

        Task task = new Task(request.title(), request.description(), creator, project);

        // A brand-new task has no previous dates, so there is nothing to
        // "snooze" here (the old snooze checks were always false).
        if (request.dueDate() != null)       task.setDueDate(request.dueDate());
        if (request.scheduledTime() != null) task.setScheduledTime(request.scheduledTime());
        if (request.priorityLevel() != null)            task.setPriorityLevel(request.priorityLevel());
        if (request.estimatedDurationMinutes() != null) task.setEstimatedDurationMinutes(request.estimatedDurationMinutes());

        if (request.assigneeId() != null) {
            task.setAssignee(validateAssignee(task.getProject().getTeam().getId(), request.assigneeId()));
        }

        if (request.isRecurring()) {
            if (request.dueDate() == null) {
                throw new IllegalArgumentException("Recurring tasks must have a due date.");
            }
            if (request.recurrenceRule() == null || !isValidRecurrenceRule(request.recurrenceRule())) {
                throw new IllegalArgumentException("Invalid recurrence rule.");
            }
            task.setRecurring(true);
            task.setRecurrenceRule(request.recurrenceRule());
            task.setRecurrenceGroupId(UUID.randomUUID());
        }

        Task saved = taskRepository.saveAndFlush(task);
        logActivity(saved, callerId, "CREATED", "Task created");
        log.info("Task {} created by user {} in project {}", saved.getId(), callerId, project.getId());

        if (saved.getAssignee() != null && !saved.getAssignee().getId().equals(callerId)) {
            notificationService.createNotification(
                saved.getAssignee(),
                "New Task Assigned",
                "You have been assigned to: " + saved.getTitle(),
                "TASK_ASSIGNED"
            );
        }

        broadcastTaskChange(saved, "TASK_CREATED");
        return TaskResponse.from(saved);
    }

    // ── Read ──────────────────────────────────────────────────────────────

    private static final java.util.Set<String> ALLOWED_TASK_SORT_FIELDS = java.util.Set.of(
            "dueDate", "scheduledTime", "createdAt", "updatedAt",
            "priorityLevel", "title", "status", "id", "estimatedDurationMinutes"
    );

    private void validateTaskSort(Pageable pageable) {
        if (pageable != null && pageable.getSort().isSorted()) {
            for (org.springframework.data.domain.Sort.Order order : pageable.getSort()) {
                if (!ALLOWED_TASK_SORT_FIELDS.contains(order.getProperty())) {
                    throw new IllegalArgumentException("Invalid sort field: " + order.getProperty());
                }
            }
        }
    }

    @Override
    public Page<TaskResponse> getTasks(UUID callerId, TaskFilterRequest filter, Pageable pageable) {
        validateTaskSort(pageable);
        if (filter.projectId() != null) {
            findProjectWithAccessOrThrow(filter.projectId(), callerId);
        }

        List<UUID> teamIds = teamMemberRepository.findTeamIdsForUser(callerId);
        if (teamIds.isEmpty()) return Page.empty(pageable);

        if (filter.teamId() != null && !teamIds.contains(filter.teamId())) {
            throw new ForbiddenException("Access denied to team " + filter.teamId());
        }

        Specification<Task> spec = TaskSpecification.of(teamIds, filter);
        return taskRepository.findAll(spec, pageable).map(TaskResponse::from);
    }

    @Override
    public TaskResponse getTaskById(UUID callerId, UUID taskId) {
        return TaskResponse.from(findTaskForReadOrThrow(callerId, taskId));
    }

    @Override
    public List<TaskActivityResponse> getTaskActivities(UUID callerId, UUID taskId) {
        findTaskForReadOrThrow(callerId, taskId);
        return taskActivityRepository.findByTaskIdOrderByCreatedAtDesc(taskId)
                .stream().map(TaskActivityResponse::from).toList();
    }

    @Override
    public List<TaskCommentResponse> getTaskComments(UUID callerId, UUID taskId) {
        findTaskForReadOrThrow(callerId, taskId);
        return taskCommentRepository.findByTaskIdOrderByCreatedAtAsc(taskId)
                .stream().map(TaskCommentResponse::from).toList();
    }
    
    @Override
    @Transactional
    public TaskCommentResponse addComment(UUID callerId, UUID taskId, String content) {
        Task task = findTaskForWriteOrThrow(callerId, taskId);
        User creator = findUserOrThrow(callerId);

        TaskComment comment = new TaskComment(task, creator, content);
        taskCommentRepository.save(comment);

        // Notify the assignee if someone else commented on their task
        if (task.getAssignee() != null && !task.getAssignee().getId().equals(callerId)) {
            notificationService.createNotification(
                    task.getAssignee(),
                    "New Comment on Task",
                    creator.getDisplayName() + " commented on: " + task.getTitle(),
                    "TASK_COMMENTED"
            );
        }

        TaskCommentResponse response = TaskCommentResponse.from(comment);
        
        broadcastTaskChange(task, "TASK_COMMENTED");
        
        return response;
    }

    @Override
    public Page<TaskResponse> getTasksByProject(UUID callerId, UUID projectId, UUID teamId, Pageable pageable) {
        validateTaskSort(pageable);
        // Single lookup: access check + project in one go.
        Project project = findProjectWithAccessOrThrow(projectId, callerId);

        if (!project.getTeam().getId().equals(teamId)) {
            throw new ForbiddenException("Project does not belong to the specified team.");
        }

        // FIX: exclude soft-deleted tasks.
        return taskRepository
                .findByProjectIdAndStatusNotOrderByDueDateAscPriorityLevelDesc(
                        projectId, TaskStatus.CANCELLED, pageable)
                .map(TaskResponse::from);
    }

    @Override
    public Page<TaskResponse> getTodaysTasks(UUID callerId, UUID teamId, String timeZoneId, Pageable pageable) {
        validateTaskSort(pageable);
        if (!teamService.hasTeamAccess(teamId, callerId)) {
            throw new ForbiddenException("You do not have access to this team.");
        }

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
        Instant endOfDay   = nowInUserZone.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant();

        return taskRepository
                .findTodaysTasksForUser(callerId, List.of(teamId), startOfDay, endOfDay, pageable)
                .map(TaskResponse::from);
    }

    @Override
    public Page<TaskResponse> getOverdueTasks(UUID callerId, UUID teamId, Pageable pageable) {
        validateTaskSort(pageable);
        if (!teamService.hasTeamAccess(teamId, callerId)) {
            throw new ForbiddenException("You do not have access to this team.");
        }

        return taskRepository
                .findOverdueTasksForUser(callerId, List.of(teamId), Instant.now(), pageable)
                .map(TaskResponse::from);
    }

    // ── Mutations ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TaskResponse updateTask(UUID callerId, UUID taskId, UpdateTaskRequest request) {
        log.debug("Updating task {} for user {}", taskId, callerId);

        Task task = findTaskForWriteOrThrow(callerId, taskId);

        if (!request.version().equals(task.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Task.class, taskId);
        }

        List<String> changedFields = new java.util.ArrayList<>();
        if (request.title() != null && !request.title().equals(task.getTitle())) {
            task.setTitle(request.title());
            changedFields.add("title");
        }
        if (request.description() != null && !request.description().equals(task.getDescription())) {
            task.setDescription(request.description());
            changedFields.add("description");
        }
        if (request.dueDate() != null && !request.dueDate().equals(task.getDueDate())) {
            if (task.getDueDate() != null && request.dueDate().isAfter(task.getDueDate())) {
                task.recordSnooze();
            }
            task.setDueDate(request.dueDate());
            changedFields.add("dueDate");
        }
        if (request.scheduledTime() != null && !request.scheduledTime().equals(task.getScheduledTime())) {
            if (task.getScheduledTime() != null && request.scheduledTime().isAfter(task.getScheduledTime())) {
                task.recordSnooze();
            }
            task.setScheduledTime(request.scheduledTime());
            changedFields.add("scheduledTime");
        }
        if (request.priorityLevel() != null && request.priorityLevel() != task.getPriorityLevel()) {
            task.setPriorityLevel(request.priorityLevel());
            changedFields.add("priorityLevel");
        }
        if (request.estimatedDurationMinutes() != null && !request.estimatedDurationMinutes().equals(task.getEstimatedDurationMinutes())) {
            task.setEstimatedDurationMinutes(request.estimatedDurationMinutes());
            changedFields.add("estimatedDurationMinutes");
        }

        // FIX: unassign support. clearAssignee wins over assigneeId.
        if (Boolean.TRUE.equals(request.clearAssignee())) {
            if (task.getAssignee() != null) {
                task.setAssignee(null);
                logActivity(task, callerId, "UNASSIGNED", "Task unassigned");
            }
        } else if (request.assigneeId() != null) {
            if (task.getAssignee() == null || !task.getAssignee().getId().equals(request.assigneeId())) {
                User newAssignee = validateAssignee(task.getProject().getTeam().getId(), request.assigneeId());
                task.setAssignee(newAssignee);
                logActivity(task, callerId, "ASSIGNED", "Task assigned to " + newAssignee.getDisplayName());
            }
        }

        if (request.isRecurring() != null) {
            if (!request.isRecurring()) {
                if (task.isRecurring()) {
                    task.setRecurring(false);
                    task.setRecurrenceRule(null);
                    changedFields.add("recurrence");
                    if (task.getRecurrenceGroupId() != null) {
                        taskRepository.cancelFutureOccurrences(task.getRecurrenceGroupId(), task.getId());
                    }
                }
            } else {
                boolean recChanged = false;
                if (!task.isRecurring()) {
                    task.setRecurring(true);
                    recChanged = true;
                }
                if (task.getDueDate() == null && request.dueDate() == null) {
                    throw new IllegalArgumentException("Recurring tasks must have a due date.");
                }
                if (task.getRecurrenceGroupId() == null) {
                    task.setRecurrenceGroupId(UUID.randomUUID());
                    recChanged = true;
                }
                if (recChanged) changedFields.add("recurrence");
            }
        }
        if (task.isRecurring() && request.recurrenceRule() != null) {
            if (!isValidRecurrenceRule(request.recurrenceRule())) {
                throw new IllegalArgumentException("Invalid recurrence rule.");
            }
            if (!request.recurrenceRule().equals(task.getRecurrenceRule())) {
                task.setRecurrenceRule(request.recurrenceRule());
                changedFields.add("recurrenceRule");
                if (task.getRecurrenceGroupId() != null) {
                    taskRepository.cancelFutureOccurrences(task.getRecurrenceGroupId(), task.getId());
                }
            }
        }

        boolean cancelled = false;
        if (request.status() != null) {
            TaskStatus oldStatus = task.getStatus();
            applyStatusTransition(task, request.status(), callerId);
            cancelled = task.getStatus() == TaskStatus.CANCELLED;
            if (request.status() == TaskStatus.COMPLETED && oldStatus != TaskStatus.COMPLETED) {
                if (task.isRecurring() && task.getRecurrenceRule() != null) {
                    handleRecurringTask(task);
                }
            }
        }

        Task saved = taskRepository.save(task);
        if (!changedFields.isEmpty()) {
            logActivity(saved, callerId, "UPDATED", "Updated fields: " + String.join(", ", changedFields));
        }
        broadcastTaskChange(saved, cancelled ? "TASK_DELETED" : "TASK_UPDATED");
        return TaskResponse.from(saved);
    }
    
    @Override
    @Transactional
    public TaskResponse rescheduleTask(UUID callerId, UUID taskId, RescheduleTaskRequest request) {
        log.debug("Rescheduling task {} for user {}", taskId, callerId);

        Task task = findTaskForWriteOrThrow(callerId, taskId);
        
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED) {
            throw new IllegalStateException("Cannot reschedule a completed or cancelled task");
        }
        
        if (!Boolean.TRUE.equals(request.clearScheduledTime()) && request.scheduledTime() == null && request.dueDate() == null) {
            throw new IllegalArgumentException("No reschedule fields provided");
        }

        if (Boolean.TRUE.equals(request.clearScheduledTime())) {
            task.setScheduledTime(null);
        } else if (request.scheduledTime() != null) {
            task.setScheduledTime(request.scheduledTime());
        }
        
        if (request.dueDate() != null) {
            task.setDueDate(request.dueDate());
        }

        Task saved = taskRepository.save(task);
        logActivity(saved, callerId, "RESCHEDULED", "Task rescheduled");
        broadcastTaskChange(saved, "TASK_UPDATED");
        return TaskResponse.from(saved);
    }

    @Override
    public List<TaskResponse> getCalendarTasks(UUID callerId, UUID teamId, Instant start, Instant end, boolean mineOnly) {
        if (!teamService.hasTeamAccess(teamId, callerId)) {
            throw new ForbiddenException("You do not have access to this team.");
        }
        
        if (start != null && end != null) {
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("Start time must be before end time");
            }
            if (java.time.Duration.between(start, end).toDays() > 62) {
                throw new IllegalArgumentException("Calendar window cannot exceed 62 days");
            }
        }
        
        Pageable limit = org.springframework.data.domain.PageRequest.of(0, 1000);
        List<Task> tasks;
        if (mineOnly) {
            tasks = taskRepository.findCalendarTasksForUser(callerId, teamId, start, end, limit);
        } else {
            tasks = taskRepository.findCalendarTasksForTeam(teamId, start, end, limit);
        }
        
        return tasks.stream().map(TaskResponse::from).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public TaskResponse completeTask(UUID callerId, UUID taskId) {
        Task task = findTaskForWriteOrThrow(callerId, taskId);
        if (task.getStatus() == TaskStatus.COMPLETED) return TaskResponse.from(task);

        task.markAsCompleted();

        if (task.isRecurring() && task.getRecurrenceRule() != null) {
            handleRecurringTask(task);
        }

        Task saved = taskRepository.save(task);
        logActivity(saved, callerId, "COMPLETED", "Task completed");
        log.info("Task {} completed at {} for user {}", saved.getId(), saved.getCompletedAt(), callerId);
        broadcastTaskChange(saved, "TASK_UPDATED");
        return TaskResponse.from(saved);
    }

    private void handleRecurringTask(Task completedTask) {
        try {
            Instant nextDueDate = calculateNextOccurrence(completedTask.getDueDate(), completedTask.getRecurrenceRule());
            if (nextDueDate != null) {
                Task nextTask = new Task(
                        completedTask.getTitle(),
                        completedTask.getDescription(),
                        completedTask.getCreator(),
                        completedTask.getProject()
                );
                nextTask.setAssignee(completedTask.getAssignee());
                nextTask.setDueDate(nextDueDate);
                nextTask.setPriorityLevel(completedTask.getPriorityLevel());
                nextTask.setRecurring(true);
                nextTask.setRecurrenceRule(completedTask.getRecurrenceRule());
                nextTask.setRecurrenceGroupId(completedTask.getRecurrenceGroupId());
                nextTask.setEstimatedDurationMinutes(completedTask.getEstimatedDurationMinutes());

                if (completedTask.getScheduledTime() != null) {
                    Instant nextScheduled = calculateNextOccurrence(completedTask.getScheduledTime(), completedTask.getRecurrenceRule());
                    nextTask.setScheduledTime(nextScheduled);
                }

                taskRepository.save(nextTask);
                log.info("Generated recurring task {} from {}", nextTask.getId(), completedTask.getId());
                broadcastTaskChange(nextTask, "TASK_CREATED");
            }
        } catch (Exception e) {
            log.error("Failed to generate recurring task: {}", e.getMessage());
        }
    }

    private Instant calculateNextOccurrence(Instant current, String rule) {
        if (current == null || rule == null) return null;
        String normalized = rule.trim().toUpperCase().replace("FREQ=", "");
        ZonedDateTime zdt = current.atZone(ZoneOffset.UTC);

        do {
            zdt = switch (normalized) {
                case "DAILY"   -> zdt.plusDays(1);
                case "WEEKLY"  -> zdt.plusWeeks(1);
                case "MONTHLY" -> zdt.plusMonths(1);
                default        -> null;
            };
        } while (zdt != null && zdt.toInstant().isBefore(Instant.now()));

        return zdt != null ? zdt.toInstant() : null;
    }

    private boolean isValidRecurrenceRule(String rule) {
        if (rule == null) return false;
        String normalized = rule.trim().toUpperCase().replace("FREQ=", "");
        return "DAILY".equals(normalized) || "WEEKLY".equals(normalized) || "MONTHLY".equals(normalized);
    }

    @Override
    @Transactional
    public TaskResponse reopenTask(UUID callerId, UUID taskId) {
        Task task = findTaskForWriteOrThrow(callerId, taskId);
        if (task.getStatus() != TaskStatus.COMPLETED) {
            throw new IllegalArgumentException(
                    "Only COMPLETED tasks can be reopened. Current status: " + task.getStatus());
        }
        task.markAsReopened();

        if (task.getRecurrenceGroupId() != null) {
            taskRepository.cancelFutureOccurrences(task.getRecurrenceGroupId(), task.getId());
        }

        Task saved = taskRepository.save(task);
        logActivity(saved, callerId, "REOPENED", "Task reopened");
        log.info("Task {} reopened (total reopens: {}) for user {}", saved.getId(), saved.getReopenedCount(), callerId);
        broadcastTaskChange(saved, "TASK_UPDATED");
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
        if (task.getDueDate() != null) {
            task.setDueDate(task.getDueDate().plus(1, java.time.temporal.ChronoUnit.DAYS));
        }
        Task saved = taskRepository.save(task);
        logActivity(saved, callerId, "SNOOZED", "Task snoozed");
        broadcastTaskChange(saved, "TASK_UPDATED");
        return TaskResponse.from(saved);
    }

    @Override
    @Transactional
    public TaskResponse deleteTask(UUID callerId, UUID taskId) {
        Task task = findTaskForWriteOrThrow(callerId, taskId);
        task.setStatus(TaskStatus.CANCELLED);
        if (task.getRecurrenceGroupId() != null) {
            taskRepository.cancelFutureOccurrences(task.getRecurrenceGroupId(), task.getId());
        }
        Task saved = taskRepository.save(task);
        logActivity(saved, callerId, "DELETED", "Task deleted");
        log.info("Task {} soft-deleted (CANCELLED) by user {}", taskId, callerId);
        broadcastTaskChange(saved, "TASK_DELETED");
        return TaskResponse.from(saved);
    }

    private static final java.util.Set<String> ALLOWED_TYPES = java.util.Set.of(
            "image/png","image/jpeg","image/gif","image/webp","application/pdf","text/plain",
            "application/zip","application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    @Override
    @Transactional
    public String addAttachment(UUID callerId, UUID taskId, org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty.");
        }
        if (file.getSize() > 25 * 1024 * 1024) {
            throw new IllegalArgumentException("File size exceeds max limit of 25MB.");
        }
        if (file.getContentType() == null || !ALLOWED_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("File type not allowed.");
        }

        Task task = findTaskForWriteOrThrow(callerId, taskId);
        String fileUrl = storageService.store(taskId, file);

        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            fileName = "unnamed_file";
        }

        com.taskmanager.core.entity.Attachment attachment = new com.taskmanager.core.entity.Attachment(task, fileName, fileUrl);
        attachmentRepository.save(attachment);

        task.getAttachments().add(attachment);

        logActivity(task, callerId, "ATTACHMENT_ADDED", "Attachment added");
        log.info("Attachment {} uploaded to task {} by user {}", fileName, taskId, callerId);
        broadcastTaskChange(task, "TASK_UPDATED");
        return fileUrl;
    }
    
    @Override
    public List<AttachmentResponse> getAttachments(UUID callerId, UUID taskId) {
        findTaskForReadOrThrow(callerId, taskId);
        return attachmentRepository.findByTaskIdOrderByUploadedAtAsc(taskId)
                .stream().map(a -> AttachmentResponse.from(taskId, a)).toList();
    }
    
    @Override
    @Transactional
    public void deleteAttachment(UUID callerId, UUID taskId, UUID attachmentId) {
        Task task = findTaskForWriteOrThrow(callerId, taskId);
        
        com.taskmanager.core.entity.Attachment attachment = task.getAttachments().stream()
                .filter(a -> a.getId().equals(attachmentId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found"));
                
        String fileUrl = attachment.getFileUrl();
        task.getAttachments().remove(attachment);
        attachmentRepository.delete(attachment);
        
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    storageService.delete(fileUrl);
                }
            }
        );
        
        logActivity(task, callerId, "ATTACHMENT_REMOVED", "Attachment removed");
        broadcastTaskChange(task, "TASK_UPDATED");
    }

    private void logActivity(Task task, UUID userId, String actionType, String description) {
        User user = userRepository.getReferenceById(userId);
        com.taskmanager.core.entity.TaskActivity activity = new com.taskmanager.core.entity.TaskActivity(task, user, actionType, description);
        taskActivityRepository.save(activity);
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private User validateAssignee(UUID teamId, UUID assigneeId) {
        User user = findUserOrThrow(assigneeId);
        if (!teamService.hasTeamAccess(teamId, assigneeId)) {
            throw new IllegalStateException("Assignee must be a member of the project's team.");
        }
        return user;
    }

    private Project findProjectWithAccessOrThrow(UUID projectId, UUID callerId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found."));

        if (!teamService.hasTeamAccess(project.getTeam().getId(), callerId)) {
            throw new ResourceNotFoundException("Project not found.");
        }

        return project;
    }

    private Task findTaskForReadOrThrow(UUID callerId, UUID taskId) {
        Task task = taskRepository.findByIdAndStatusNot(taskId, TaskStatus.CANCELLED)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));

        if (!teamService.hasTeamAccess(task.getProject().getTeam().getId(), callerId)) {
            throw new ResourceNotFoundException("Task not found.");
        }

        return task;
    }

    private Task findTaskForWriteOrThrow(UUID callerId, UUID taskId) {
        return findTaskForReadOrThrow(callerId, taskId);
    }

    private void broadcastTaskChange(Task task, String type) {
        try {
            UUID teamId = task.getProject().getTeam().getId();
            TaskResponse taskPayload = TaskResponse.from(task);

            eventPublisher.publishEvent(new com.taskmanager.core.event.TaskChangeEvent(
                    teamId,
                    task.getId(),
                    task.getProject().getId(),
                    type,
                    taskPayload
            ));
        } catch (Exception e) {
            log.error("Failed to publish task change event: {}", e.getMessage());
        }
    }

    /**
     * Legal transitions (FIX — the old version accepted anything):
     *   same status            -> no-op
     *   CANCELLED  -> anything -> rejected (cancelled tasks are soft-deleted)
     *   COMPLETED  -> REOPENED   (counts a reopen, clears completedAt)
     *   COMPLETED  -> CANCELLED
     *   COMPLETED  -> PENDING / IN_PROGRESS -> rejected, because it would leave
     *                 completedAt set and skip the reopen counter that the
     *                 RL data depends on. Use REOPENED instead.
     *   anything else -> PENDING / IN_PROGRESS / COMPLETED / CANCELLED as requested
     *   REOPENED   only from COMPLETED
     */
    private void applyStatusTransition(Task task, TaskStatus target, UUID callerId) {
        TaskStatus current = task.getStatus();
        if (current == target) return;

        if (current == TaskStatus.CANCELLED) {
            throw new IllegalStateException("Cancelled tasks cannot be modified.");
        }
        if (current == TaskStatus.COMPLETED
                && target != TaskStatus.REOPENED
                && target != TaskStatus.CANCELLED) {
            throw new IllegalStateException(
                    "A completed task can only be reopened (status REOPENED) or cancelled.");
        }

        switch (target) {
            case IN_PROGRESS -> task.markAsStarted();
            case COMPLETED   -> task.markAsCompleted();
            case REOPENED    -> {
                if (current != TaskStatus.COMPLETED) {
                    throw new IllegalStateException("Only COMPLETED tasks can be reopened.");
                }
                task.markAsReopened();
            }
            case CANCELLED   -> task.setStatus(TaskStatus.CANCELLED);
            case PENDING     -> task.setStatus(TaskStatus.PENDING);
        }
        logActivity(task, callerId, "STATUS_CHANGED", "Status changed to " + target.name());
    }
}
