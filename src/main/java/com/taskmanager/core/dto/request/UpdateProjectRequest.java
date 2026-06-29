package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/v1/projects/{projectId}
 *
 * All fields nullable — service only applies non-null values.
 * Status is intentionally excluded here: use the dedicated
 * POST /archive and POST /reactivate sub-resources instead,
 * because status transitions are domain events, not data edits.
 */
public record UpdateProjectRequest(

        @Size(min = 1, max = 150, message = "Name must be between 1 and 150 characters")
        String name,

        @Size(max = 500, message = "Description cannot exceed 500 characters")
        String description,

        @Pattern(
                regexp = "^#([A-Fa-f0-9]{6})$",
                message = "Color must be a valid hex code (e.g. #FF6B6B)"
        )
        String color

) {}
