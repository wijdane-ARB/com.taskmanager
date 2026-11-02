package com.taskmanager.core.dto.response;

import com.taskmanager.core.entity.Task;
import com.taskmanager.core.enums.PriorityLevel;
import com.taskmanager.core.enums.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The outbound DTO separating the API contract from the persistence model.
 * RL behavioral fields are surfaced for user self-awareness and transparent ML training.
 */
public record TaskResponse(

        UUID id,
        String title,
        String description,
        UUID projectId,
        String projectName,
        UUID creatorId,
        String creatorName,
        UUID assigneeId,
        String assigneeName,

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
        Instant updatedAt,
        Long version,

        // Attachments
        java.util.List<String> attachmentUrls

) {
    /**
     * Static factory method for clean mapping from entity to DTO.
     * Call Task.getProject() carefully — the project is lazily loaded,
     * so this must only be called within an active transaction.
     */
    public static TaskResponse from(Task task) {
        // Safe mapping of attachments if loaded
        java.util.List<String> urls = new java.util.ArrayList<>();
        if (task.getAttachments() != null) {
            for (com.taskmanager.core.entity.Attachment a : task.getAttachments()) {
                // Route all attachments through the secure AttachmentController
                urls.add("/api/v1/tasks/" + task.getId() + "/attachments/" + a.getId());
            }
        }

        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getProject().getId(),
                task.getProject().getName(),
                task.getCreator() != null ? task.getCreator().getId() : null,
                task.getCreator() != null ? task.getCreator().getDisplayName() : "Unknown",
                task.getAssignee() != null ? task.getAssignee().getId() : null,
                task.getAssignee() != null ? task.getAssignee().getDisplayName() : null,
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
                task.getUpdatedAt(),
                task.getVersion(),
                urls
        );
    }
}