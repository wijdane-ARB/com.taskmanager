package com.taskmanager.core.dto.response;

import com.taskmanager.core.entity.Task;
import com.taskmanager.core.enums.PriorityLevel;
import com.taskmanager.core.enums.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The outbound DTO. Never expose the Task entity directly —
 * this separates the API contract from the persistence model,
 * so we can evolve either independently.
 *
 * RL fields ARE included in the response. The React Native client
 * displays estimated vs actual duration on the completion screen
 * ("You estimated 30 min — it took 47 min") which is a UX nudge
 * toward better self-awareness and produces richer training data.
 */
public record TaskResponse(

        UUID id,
        String title,
        String description,
        UUID projectId,
        String projectName,
        UUID ownerId,

        // Scheduling
        Instant dueDate,
        Instant scheduledTime,
        boolean isRecurring,
        String recurrenceRule,

        // Status & priority
        TaskStatus status,
        PriorityLevel priorityLevel,

        // RL behavioral fields (surfaced to the UI for user transparency)
        Instant actualStartTime,
        Instant completedAt,
        Integer estimatedDurationMinutes,
        int reopenedCount,
        int snoozedCount,

        // Audit
        Instant createdAt,
        Instant updatedAt

) {
    /**
     * Static factory method for clean mapping from entity to DTO.
     * Call Task.getProject() carefully — the project is lazily loaded,
     * so this must only be called within an active transaction.
     */
    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getProject().getId(),
                task.getProject().getName(),
                task.getOwner().getId(),
                task.getDueDate(),
                task.getScheduledTime(),
                task.isRecurring(),
                task.getRecurrenceRule(),
                task.getStatus(),
                task.getPriorityLevel(),
                task.getActualStartTime(),
                task.getCompletedAt(),
                task.getEstimatedDurationMinutes(),
                task.getReopenedCount(),
                task.getSnoozedCount(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}