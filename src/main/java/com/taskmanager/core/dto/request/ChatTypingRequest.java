package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ChatTypingRequest(
        @NotNull UUID teamId,
        @NotNull Boolean isTyping
) {}