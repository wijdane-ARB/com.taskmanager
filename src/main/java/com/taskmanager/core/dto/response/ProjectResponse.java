package com.taskmanager.core.dto.response;

import com.taskmanager.core.entity.Project;
import com.taskmanager.core.enums.ProjectStatus;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String description,
        String color,
        ProjectStatus status,
        long activeTaskCount,
        UUID teamId,
        UUID createdByUserId,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectResponse from(Project project, long activeTaskCount) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getColor(),
                project.getStatus(),
                activeTaskCount,
                project.getTeam().getId(),
                project.getCreatedBy().getId(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}