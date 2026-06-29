package com.taskmanager.core.dto.request;

import com.taskmanager.core.enums.PriorityLevel;
import com.taskmanager.core.enums.TaskStatus;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.util.UUID;

/**
 * Query parameters for GET /api/v1/tasks
 *
 * All fields are nullable — omitting a field means "no filter on this dimension".
 * Spring MVC binds these automatically from query params:
 *
 *   GET /api/v1/tasks?status=PENDING&priority=HIGH&projectId=...&dueBefore=...
 *
 * Flutter usage examples:
 *   - Home screen (active, high priority):  ?status=PENDING&priority=HIGH
 *   - Project task list:                    ?projectId=<uuid>&status=PENDING
 *   - Upcoming this week:                   ?dueBefore=<next-sunday-iso>&status=PENDING
 *   - Search by keyword:                    ?keyword=dentist
 *
 * Instant fields must be sent as ISO-8601 strings, e.g. "2025-06-01T00:00:00Z".
 */
public record TaskFilterRequest(

        // Filter by project — null means across all projects
        UUID projectId,

        // Filter by status — null means all non-CANCELLED statuses
        // (CANCELLED tasks are soft-deleted and never returned regardless)
        TaskStatus status,

        // Filter by priority level — null means all priorities
        PriorityLevel priority,

        // Return tasks whose dueDate is before this instant
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant dueBefore,

        // Return tasks whose dueDate is on or after this instant
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant dueAfter,

        // Substring match on title (case-insensitive)
        // Backed by ILIKE in Postgres via Specification
        String keyword

) {}
