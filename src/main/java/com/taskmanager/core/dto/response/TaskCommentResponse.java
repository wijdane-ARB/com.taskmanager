package com.taskmanager.core.dto.response;

import com.taskmanager.core.entity.TaskComment;
import java.time.Instant;
import java.util.UUID;

public record TaskCommentResponse(
        UUID id,
        UUID taskId,
        UUID userId,
        String userName,
        String content,
        Instant createdAt
) {
    public static TaskCommentResponse from(TaskComment comment) {
        return new TaskCommentResponse(
                comment.getId(),
                comment.getTask().getId(),
                comment.getUser().getId(),
                comment.getUser().getDisplayName(),
                comment.getContent(),
                comment.getCreatedAt()
        );
    }
}