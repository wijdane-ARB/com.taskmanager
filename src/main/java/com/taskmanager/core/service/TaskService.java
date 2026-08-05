package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.CreateTaskRequest;
import com.taskmanager.core.dto.request.TaskFilterRequest;
import com.taskmanager.core.dto.request.UpdateTaskRequest;
import com.taskmanager.core.dto.response.TaskResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TaskService {

    TaskResponse createTask(UUID ownerId, CreateTaskRequest request);

    /**
     * General-purpose filtered + paginated task query.
     * Activates the previously dead-code TaskFilterRequest / TaskSpecification.
     * All filter fields are optional; omit all for "all my active tasks".
     */
    Page<TaskResponse> getTasks(UUID ownerId, TaskFilterRequest filter, Pageable pageable);

    TaskResponse getTaskById(UUID ownerId, UUID taskId);

    Page<TaskResponse> getTasksByProject(UUID ownerId, UUID projectId, Pageable pageable);

    /** Paginated — replaces the unbounded List<TaskResponse> variant. */
    Page<TaskResponse> getTodaysTasks(UUID ownerId, String timeZoneId, Pageable pageable);
    /** Paginated — replaces the unbounded List<TaskResponse> variant. */
    Page<TaskResponse> getOverdueTasks(UUID ownerId, Pageable pageable);

    TaskResponse updateTask(UUID ownerId, UUID taskId, UpdateTaskRequest request);

    TaskResponse completeTask(UUID ownerId, UUID taskId);

    TaskResponse reopenTask(UUID ownerId, UUID taskId);

    TaskResponse recordSnooze(UUID ownerId, UUID taskId);

    TaskResponse deleteTask(UUID ownerId, UUID taskId);
}