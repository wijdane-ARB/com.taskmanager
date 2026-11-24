package com.taskmanager.core.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.taskmanager.core.enums.PriorityLevel;
import com.taskmanager.core.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The version is required. Omitted editable fields preserve their current values.
 * An explicit null githubUrl clears the link.
 *
 * FIX: null assigneeId means "no change", so there was no way to unassign a
 * task. Send clearAssignee=true to set the assignee to null. If both are
 * sent, clearAssignee wins.
 */
public record UpdateTaskRequest(

        @Size(min = 1, max = 255)
        String title,

        @Size(max = 2000)
        String description,

        UUID assigneeId,

        Boolean clearAssignee,

        Instant dueDate,

        Instant scheduledTime,

        PriorityLevel priorityLevel,

        // Status transitions are validated in the service layer
        // (see TaskServiceImpl.applyStatusTransition).
        TaskStatus status,

        @Min(1)
        Integer estimatedDurationMinutes,

        Boolean isRecurring,

        @Size(max = 100)
        String recurrenceRule,

        @NotNull(message = "Version is required for optimistic locking")
        Long version,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(implementation = String.class, nullable = true, description = "HTTPS GitHub issue or pull request URL. Send null to clear.")
        JsonNode githubUrl

) {
    public UpdateTaskRequest(String title, String description, UUID assigneeId, Boolean clearAssignee,
                             Instant dueDate, Instant scheduledTime, PriorityLevel priorityLevel, TaskStatus status,
                             Integer estimatedDurationMinutes, Boolean isRecurring, String recurrenceRule, Long version) {
        this(title, description, assigneeId, clearAssignee, dueDate, scheduledTime, priorityLevel, status,
                estimatedDurationMinutes, isRecurring, recurrenceRule, version, null);
    }
}
