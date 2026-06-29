package com.taskmanager.core.dto.response;

import com.taskmanager.core.entity.Project;
import com.taskmanager.core.enums.ProjectStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound DTO for Project. Never expose the entity directly —
 * the entity carries the lazy tasks collection which would either
 * trigger an N+1 load or throw a LazyInitializationException outside
 * a transaction if accidentally serialized.
 *
 * taskCount is included so Flutter can display "12 tasks" on the
 * project card without making a second API call.
 *
 * color is the hex string the Flutter UI uses for project avatars
 * and list accents (e.g. "#6366F1").
 */
public record ProjectResponse(

        UUID id,
        String name,
        String description,
        String color,
        ProjectStatus status,

        // Denormalized count — computed in the service layer.
        // Excludes COMPLETED and CANCELLED tasks so the badge
        // reflects active work remaining, not total historical tasks.
        long activeTaskCount,

        UUID ownerId,
        Instant createdAt,
        Instant updatedAt

) {
    /**
     * Maps from entity + pre-computed task count.
     * The count is passed in rather than derived here because
     * Task is a lazy collection on Project — calling tasks.size()
     * here would either blow up or load everything into memory.
     */
    public static ProjectResponse from(Project project, long activeTaskCount) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getColor(),
                project.getStatus(),
                activeTaskCount,
                project.getOwner().getId(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
