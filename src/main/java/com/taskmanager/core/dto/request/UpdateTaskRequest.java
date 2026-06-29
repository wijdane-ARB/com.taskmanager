package com.taskmanager.core.dto.request;

import com.taskmanager.core.enums.PriorityLevel;
import com.taskmanager.core.enums.TaskStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * All fields are Optional (nullable) — PATCH semantics.
 * The service layer only applies fields that are non-null.
 * This avoids requiring a full object representation for simple updates
 * like marking a task complete or changing a due date.
 */
public record UpdateTaskRequest(

        @Size(min = 1, max = 255)
        String title,

        @Size(max = 2000)
        String description,

        Instant dueDate,

        Instant scheduledTime,

        PriorityLevel priorityLevel,

        // Status transitions are driven through this field.
        // The service layer validates that the transition is legal
        // (e.g., you can't go from CANCELLED to COMPLETED).
        TaskStatus status,

        @Min(1)
        Integer estimatedDurationMinutes,

        Boolean isRecurring,

        @Size(max = 100)
        String recurrenceRule

) {}