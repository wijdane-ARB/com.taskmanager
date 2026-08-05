package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateProjectRequest(

        @NotNull(message = "Team ID is required")
        UUID teamId,

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