// PATH: src/main/java/com/taskmanager/core/dto/response/ChatMessageResponse.java
package com.taskmanager.core.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageResponse(
        UUID id,
        UUID teamId,
        UUID senderId,
        String senderName,
        String content,
        Instant timestamp,
        UUID clientId
) {}
