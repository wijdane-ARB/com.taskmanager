package com.taskmanager.core.dto.request;

import java.time.Instant;

/**
 * Body of PATCH /api/v1/tasks/{id}/reschedule — what a calendar drag-and-drop sends.
 * At least one of the three fields must be set.
 *
 * Unlike a normal PATCH, rescheduling never increments snoozedCount, so moving
 * a task around the calendar does not pollute the behaviour data.
 *
 * clearScheduledTime=true removes the time slot (dragging a task back to the
 * "unscheduled" tray). It wins over scheduledTime if both are sent.
 */
public record RescheduleTaskRequest(
        Instant scheduledTime,
        Instant dueDate,
        Boolean clearScheduledTime
) {}