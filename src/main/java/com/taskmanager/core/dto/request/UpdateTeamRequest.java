package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateTeamRequest(
        @NotBlank @Size(max = 100) String name
) {}