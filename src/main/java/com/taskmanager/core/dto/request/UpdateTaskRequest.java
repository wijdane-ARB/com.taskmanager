package com.taskmanager.core.dto.request;

import com.taskmanager.core.enums.PriorityLevel;
import com.taskmanager.core.enums.TaskStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * All fields are Optional (nullable) — PATCH semantics.
 * The service layer only applies fields that are non-null.
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

        @jakarta.validation.constraints.NotNull(message = "Version is required for optimistic locking")
        Long version

) {}