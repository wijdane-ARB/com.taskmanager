package com.taskmanager.core.dto.response;

import com.taskmanager.core.entity.Attachment;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(
        UUID id,
        UUID taskId,
        String fileName,
        String url,
        Instant uploadedAt
) {
    public static AttachmentResponse from(UUID taskId, Attachment a) {
        return new AttachmentResponse(
                a.getId(),
                taskId,
                a.getFileName(),
                "/api/v1/tasks/" + taskId + "/attachments/" + a.getId(),
                a.getUploadedAt()
        );
    }
}