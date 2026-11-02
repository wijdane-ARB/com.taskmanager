package com.taskmanager.core.controller;

import com.taskmanager.core.dto.request.CreateTaskRequest;
import com.taskmanager.core.dto.request.TaskFilterRequest;
import com.taskmanager.core.dto.request.UpdateTaskRequest;
import com.taskmanager.core.dto.response.ApiResponse;
import com.taskmanager.core.dto.response.TaskResponse;
import com.taskmanager.core.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * All endpoints are prefixed with /api/v1/tasks.
 *
 * Changes from the original:
 *
 * 1. GET /api/v1/tasks — NEW endpoint that activates the existing
 *    TaskFilterRequest / TaskSpecification dead code. Supports filtering by
 *    projectId, status, priority, dueBefore, dueAfter, and keyword, all
 *    paginated. This replaces the need for ad-hoc one-off query methods.
 *
 * 2. GET /api/v1/tasks/today and /overdue are now paginated via Pageable.
 *    "Today" for a power user can be 50+ tasks; returning an unbounded list
 *    risks OOM on the server and slow renders on the client. Default page
 *    size 20; Flutter passes ?page=1 to load more.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * POST /api/v1/tasks
     */
    @PostMapping
    public ResponseEntity<ApiResponse<TaskResponse>> createTask(
            @AuthenticationPrincipal UUID currentUserId,
            @Valid @RequestBody CreateTaskRequest request
    ) {
        TaskResponse task = taskService.createTask(currentUserId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Task created successfully", task));
    }

    /**
     * GET /api/v1/tasks?projectId=&status=&priority=&dueBefore=&dueAfter=&keyword=
     *
     * Previously TaskFilterRequest existed but was never bound to any endpoint —
     * the Specification was written but never called. This endpoint activates it.
     *
     * All filter params are optional. Omit all of them for "all my active tasks".
     * CANCELLED tasks are always excluded regardless of what the status param says.
     *
     * Pagination via standard Pageable query params:
     *   ?page=0&size=20&sort=dueDate,asc
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<TaskResponse>>> getTasks(
            @AuthenticationPrincipal UUID currentUserId,
            @ModelAttribute TaskFilterRequest filter,
            @PageableDefault(size = 20, sort = "dueDate") Pageable pageable
    ) {
        Page<TaskResponse> tasks = taskService.getTasks(currentUserId, filter, pageable);
        return ResponseEntity.ok(ApiResponse.ok(tasks));
    }

    /**
     * GET /api/v1/tasks/{taskId}
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<TaskResponse>> getTask(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId
    ) {
        TaskResponse task = taskService.getTaskById(currentUserId, taskId);
        return ResponseEntity.ok(ApiResponse.ok(task));
    }

    /** GET /api/v1/tasks/{taskId}/activities */
    @GetMapping("/{taskId}/activities")
    public ResponseEntity<ApiResponse<java.util.List<com.taskmanager.core.dto.response.TaskActivityResponse>>> getActivities(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(taskService.getTaskActivities(currentUserId, taskId)));
    }

    /** GET /api/v1/tasks/{taskId}/comments */
    @GetMapping("/{taskId}/comments")
    public ResponseEntity<ApiResponse<java.util.List<com.taskmanager.core.dto.response.TaskCommentResponse>>> getComments(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(taskService.getTaskComments(currentUserId, taskId)));
    }

    /**
     * GET /api/v1/tasks/project/{projectId}?teamId={teamId}&page=0&size=20&sort=dueDate,asc
     */
    @GetMapping("/project/{projectId}")
    public ResponseEntity<ApiResponse<Page<TaskResponse>>> getTasksByProject(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID projectId,
            @RequestParam UUID teamId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<TaskResponse> tasks = taskService.getTasksByProject(currentUserId, projectId, teamId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(tasks));
    }

    /**
     * GET /api/v1/tasks/today?teamId={teamId}&page=0&size=20
     *
     * Was List<TaskResponse> — changed to Page<TaskResponse>.
     * A user with many projects can have dozens of tasks due today.
     * Returning all of them in one payload risks slow first paint on mobile
     * and unbounded memory usage on the server.
     *
     * Flutter's home screen loads page 0 on launch; a "load more" widget
     * appends subsequent pages as the user scrolls.
     */
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Page<TaskResponse>>> getTodaysTasks(
            @AuthenticationPrincipal UUID currentUserId,
            @RequestParam UUID teamId,
            @RequestHeader(value = "X-Timezone", required = false) String timeZoneId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<TaskResponse> tasks = taskService.getTodaysTasks(currentUserId, teamId, timeZoneId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(tasks));
    }

    /**
     * GET /api/v1/tasks/overdue?teamId={teamId}&page=0&size=20
     *
     * Was List<TaskResponse> — changed to Page<TaskResponse> for the same
     * reason as /today. Overdue lists in particular can be arbitrarily long
     * for users who haven't logged in for weeks.
     */
    @GetMapping("/overdue")
    public ResponseEntity<ApiResponse<Page<TaskResponse>>> getOverdueTasks(
            @AuthenticationPrincipal UUID currentUserId,
            @RequestParam UUID teamId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<TaskResponse> tasks = taskService.getOverdueTasks(currentUserId, teamId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(tasks));
    }

    /**
     * PATCH /api/v1/tasks/{taskId}
     */
    @PatchMapping("/{taskId}")
    public ResponseEntity<ApiResponse<TaskResponse>> updateTask(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId,
            @Valid @RequestBody UpdateTaskRequest request
    ) {
        TaskResponse task = taskService.updateTask(currentUserId, taskId, request);
        return ResponseEntity.ok(ApiResponse.ok("Task updated", task));
    }

    /** POST /api/v1/tasks/{taskId}/complete */
    @PostMapping("/{taskId}/complete")
    public ResponseEntity<ApiResponse<TaskResponse>> completeTask(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId
    ) {
        TaskResponse task = taskService.completeTask(currentUserId, taskId);
        return ResponseEntity.ok(ApiResponse.ok("Task completed", task));
    }

    /** POST /api/v1/tasks/{taskId}/reopen */
    @PostMapping("/{taskId}/reopen")
    public ResponseEntity<ApiResponse<TaskResponse>> reopenTask(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId
    ) {
        TaskResponse task = taskService.reopenTask(currentUserId, taskId);
        return ResponseEntity.ok(ApiResponse.ok("Task reopened", task));
    }

    /** POST /api/v1/tasks/{taskId}/snooze */
    @PostMapping("/{taskId}/snooze")
    public ResponseEntity<ApiResponse<TaskResponse>> snoozeTask(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId
    ) {
        TaskResponse task = taskService.recordSnooze(currentUserId, taskId);
        return ResponseEntity.ok(ApiResponse.ok("Snooze recorded", task));
    }

    /** DELETE /api/v1/tasks/{taskId} — soft-delete (CANCELLED) */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<ApiResponse<TaskResponse>> deleteTask(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId
    ) {
        TaskResponse task = taskService.deleteTask(currentUserId, taskId);
        return ResponseEntity.ok(ApiResponse.ok("Task deleted", task));
    }

    /** POST /api/v1/tasks/{taskId}/attachments */
    @PostMapping(value = "/{taskId}/attachments", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<String>> uploadAttachment(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file
    ) {
        String fileUrl = taskService.addAttachment(currentUserId, taskId, file);
        return ResponseEntity.ok(ApiResponse.ok("Attachment uploaded successfully", fileUrl));
    }
}