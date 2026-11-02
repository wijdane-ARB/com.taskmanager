package com.taskmanager.core.controller;

import com.taskmanager.core.dto.request.CreateProjectRequest;
import com.taskmanager.core.dto.request.UpdateProjectRequest;
import com.taskmanager.core.dto.response.ApiResponse;
import com.taskmanager.core.dto.response.ProjectResponse;
import com.taskmanager.core.enums.ProjectStatus;
import com.taskmanager.core.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** POST /api/v1/projects — teamId now lives in the request body. */
    @PostMapping
    public ResponseEntity<ApiResponse<ProjectResponse>> createProject(
            @AuthenticationPrincipal UUID currentUserId,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        ProjectResponse project = projectService.createProject(currentUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Project created successfully", project));
    }

    /** GET /api/v1/projects?teamId=&status=ACTIVE — teamId is now required. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectResponse>>> getProjects(
            @AuthenticationPrincipal UUID currentUserId,
            @RequestParam UUID teamId,
            @RequestParam(defaultValue = "ACTIVE") ProjectStatus status
    ) {
        List<ProjectResponse> projects = projectService.getProjects(currentUserId, teamId, status);
        return ResponseEntity.ok(ApiResponse.ok(projects));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ApiResponse<ProjectResponse>> getProject(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID projectId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(projectService.getProjectById(currentUserId, projectId)));
    }

    @PatchMapping("/{projectId}")
    public ResponseEntity<ApiResponse<ProjectResponse>> updateProject(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok("Project updated",
                projectService.updateProject(currentUserId, projectId, request)));
    }

    @PostMapping("/{projectId}/archive")
    public ResponseEntity<ApiResponse<ProjectResponse>> archiveProject(
            @AuthenticationPrincipal UUID currentUserId, @PathVariable UUID projectId
    ) {
        return ResponseEntity.ok(ApiResponse.ok("Project archived",
                projectService.archiveProject(currentUserId, projectId)));
    }

    @PostMapping("/{projectId}/complete")
    public ResponseEntity<ApiResponse<ProjectResponse>> completeProject(
            @AuthenticationPrincipal UUID currentUserId, @PathVariable UUID projectId
    ) {
        return ResponseEntity.ok(ApiResponse.ok("Project completed",
                projectService.completeProject(currentUserId, projectId)));
    }

    @PostMapping("/{projectId}/reactivate")
    public ResponseEntity<ApiResponse<ProjectResponse>> reactivateProject(
            @AuthenticationPrincipal UUID currentUserId, @PathVariable UUID projectId
    ) {
        return ResponseEntity.ok(ApiResponse.ok("Project reactivated",
                projectService.reactivateProject(currentUserId, projectId)));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<ApiResponse<Void>> deleteProject(
            @AuthenticationPrincipal UUID currentUserId, @PathVariable UUID projectId
    ) {
        projectService.deleteProject(currentUserId, projectId);
        return ResponseEntity.ok(ApiResponse.ok("Project deleted", null));
    }
}