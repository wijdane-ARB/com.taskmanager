package com.taskmanager.core.dto.request;

import com.taskmanager.core.enums.PriorityLevel;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

/**
 * All fields map directly to Task entity fields.
 * The controller validates this with @Valid before it reaches the service.
 */
public record CreateTaskRequest(

        @NotBlank(message = "Task title is required")
        @Size(min = 1, max = 255, message = "Title must be between 1 and 255 characters")
        String title,

        @Size(max = 2000, message = "Description cannot exceed 2000 characters")
        String description,

        @NotNull(message = "Project ID is required")
        UUID projectId,

        UUID assigneeId,

        Instant dueDate,

        Instant scheduledTime,

        PriorityLevel priorityLevel,

        @Min(value = 1, message = "Estimated duration must be at least 1 minute")
        Integer estimatedDurationMinutes,

        boolean isRecurring,

        @Size(max = 100)
        String recurrenceRule

) {}