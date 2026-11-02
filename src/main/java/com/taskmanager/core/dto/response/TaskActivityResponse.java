package com.taskmanager.core.dto.response;

import com.taskmanager.core.entity.TaskActivity;
import java.time.Instant;
import java.util.UUID;

public record TaskActivityResponse(
        UUID id,
        UUID taskId,
        UUID userId,
        String userName,
        String actionType,
        String description,
        Instant createdAt
) {
    public static TaskActivityResponse from(TaskActivity activity) {
        return new TaskActivityResponse(
                activity.getId(),
                activity.getTask().getId(),
                activity.getUser().getId(),
                activity.getUser().getDisplayName(),
                activity.getActionType(),
                activity.getDescription(),
                activity.getCreatedAt()
        );
    }
}