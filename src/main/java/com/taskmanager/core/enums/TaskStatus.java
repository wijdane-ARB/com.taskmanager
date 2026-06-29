package com.taskmanager.core.enums;

/**
 * REOPENED is a critical status for the RL model — it signals that
 * a task was marked complete prematurely and correlates with poor
 * time estimation. The reopenedCount field on Task tracks frequency.
 */
public enum TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    REOPENED,
    CANCELLED
}