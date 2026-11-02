package com.taskmanager.core.controller;

import com.taskmanager.core.dto.request.CreateCommentRequest;
import com.taskmanager.core.dto.response.ApiResponse;
import com.taskmanager.core.dto.response.TaskCommentResponse;
import com.taskmanager.core.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * POST /api/v1/tasks/{taskId}/comments
 * (GET for the same path already lives in TaskController.)
 */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/comments")
public class CommentController {

    private final TaskService taskService;

    public CommentController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TaskCommentResponse>> addComment(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId,
            @Valid @RequestBody CreateCommentRequest request
    ) {
        TaskCommentResponse comment = taskService.addComment(currentUserId, taskId, request.content().trim());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Comment added", comment));
    }
}