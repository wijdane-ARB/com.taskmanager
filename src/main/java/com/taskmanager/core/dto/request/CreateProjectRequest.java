package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/projects
 *
 * color is optional — the service defaults to "#6366F1" (indigo) if absent,
 * matching the entity default. Flutter's color picker sends a hex string;
 * the @Pattern ensures it's a valid 6-digit hex code.
 */
public record CreateProjectRequest(

        @NotBlank(message = "Project name is required")
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
