package com.taskmanager.core.event;

import com.taskmanager.core.dto.response.TaskResponse;
import java.util.UUID;

public record TaskChangeEvent(
        UUID teamId,
        UUID taskId,
        UUID projectId,
        String type,
        TaskResponse taskPayload
) {}