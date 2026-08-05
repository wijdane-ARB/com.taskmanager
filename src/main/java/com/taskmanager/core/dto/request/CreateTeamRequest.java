package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTeamRequest(

        @NotBlank(message = "Team name is required")
        @Size(min = 1, max = 150, message = "Name must be between 1 and 150 characters")
        String name

) {}