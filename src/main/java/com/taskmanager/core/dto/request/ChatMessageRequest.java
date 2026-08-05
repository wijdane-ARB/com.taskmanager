// PATH: src/main/java/com/taskmanager/core/dto/request/ChatMessageRequest.java
package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ChatMessageRequest(
        @NotNull UUID teamId,
        @NotBlank String content
) {}