package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ChatMessageRequest(
        @NotNull UUID teamId,
        @NotBlank @Size(max = 2000) String content,
        UUID clientId
) {}