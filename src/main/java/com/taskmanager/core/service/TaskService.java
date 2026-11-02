package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.CreateTaskRequest;
import com.taskmanager.core.dto.request.RescheduleTaskRequest;
import com.taskmanager.core.dto.request.TaskFilterRequest;
import com.taskmanager.core.dto.request.UpdateTaskRequest;
import com.taskmanager.core.dto.response.AttachmentResponse;
import com.taskmanager.core.dto.response.TaskActivityResponse;
import com.taskmanager.core.dto.response.TaskCommentResponse;
import com.taskmanager.core.dto.response.TaskResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TaskService {

    TaskResponse createTask(UUID ownerId, CreateTaskRequest request);

    /** General-purpose filtered + paginated task query. */
    Page<TaskResponse> getTasks(UUID ownerId, TaskFilterRequest filter, Pageable pageable);

    TaskResponse getTaskById(UUID ownerId, UUID taskId);
    List<TaskActivityResponse> getTaskActivities(UUID callerId, UUID taskId);
    List<TaskCommentResponse> getTaskComments(UUID callerId, UUID taskId);

    /** Adds a comment, logs an activity, and notifies the creator/assignee. */
    TaskCommentResponse addComment(UUID callerId, UUID taskId, String content);

    Page<TaskResponse> getTasksByProject(UUID callerId, UUID projectId, UUID teamId, Pageable pageable);

    Page<TaskResponse> getTodaysTasks(UUID callerId, UUID teamId, String timeZoneId, Pageable pageable);
    Page<TaskResponse> getOverdueTasks(UUID callerId, UUID teamId, Pageable pageable);
    
    List<TaskResponse> getCalendarTasks(UUID callerId, UUID teamId, Instant start, Instant end, boolean mineOnly);

    TaskResponse updateTask(UUID ownerId, UUID taskId, UpdateTaskRequest request);
    
    TaskResponse rescheduleTask(UUID callerId, UUID taskId, RescheduleTaskRequest request);

    TaskResponse completeTask(UUID ownerId, UUID taskId);

    TaskResponse reopenTask(UUID ownerId, UUID taskId);

    TaskResponse recordSnooze(UUID ownerId, UUID taskId);

    TaskResponse deleteTask(UUID ownerId, UUID taskId);

    String addAttachment(UUID callerId, UUID taskId, org.springframework.web.multipart.MultipartFile file);

    List<AttachmentResponse> getAttachments(UUID callerId, UUID taskId);

    void deleteAttachment(UUID callerId, UUID taskId, UUID attachmentId);
}