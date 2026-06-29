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

/**
 * Changes from the original:
 *
 * Added POST /api/v1/projects/{projectId}/complete — the ProjectStatus.COMPLETED
 * enum value existed but had no reachable transition in either the controller
 * or the service. This left it as dead code in the enum and made the status
 * permanently unreachable for any project. The new endpoint mirrors the
 * /archive and /reactivate pattern.
 *
 * Completed projects are hidden from the active home screen (same as ARCHIVED)
 * but carry semantic meaning: all tasks are done. Flutter can display a
 * "🎉 Project completed" celebration screen on this status.
 * Completed projects count toward the free-tier limit exactly like ACTIVE ones —
 * they are not a loophole.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectResponse>> createProject(
            @AuthenticationPrincipal UUID currentUserId,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        ProjectResponse project = projectService.createProject(currentUserId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Project created successfully", project));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectResponse>>> getProjects(
            @AuthenticationPrincipal UUID currentUserId,
            @RequestParam(defaultValue = "ACTIVE") ProjectStatus status
    ) {
        List<ProjectResponse> projects = projectService.getProjects(currentUserId, status);
        return ResponseEntity.ok(ApiResponse.ok(projects));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ApiResponse<ProjectResponse>> getProject(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID projectId
    ) {
        ProjectResponse project = projectService.getProjectById(currentUserId, projectId);
        return ResponseEntity.ok(ApiResponse.ok(project));
    }

    @PatchMapping("/{projectId}")
    public ResponseEntity<ApiResponse<ProjectResponse>> updateProject(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request
    ) {
        ProjectResponse project = projectService.updateProject(currentUserId, projectId, request);
        return ResponseEntity.ok(ApiResponse.ok("Project updated", project));
    }

    @PostMapping("/{projectId}/archive")
    public ResponseEntity<ApiResponse<ProjectResponse>> archiveProject(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID projectId
    ) {
        ProjectResponse project = projectService.archiveProject(currentUserId, projectId);
        return ResponseEntity.ok(ApiResponse.ok("Project archived", project));
    }

    /**
     * POST /api/v1/projects/{projectId}/complete
     *
     * Marks a project as COMPLETED — all tasks are done, project is finished.
     * Valid transitions: ACTIVE → COMPLETED.
     * From COMPLETED, a project can be archived (COMPLETED → ARCHIVED) or
     * reactivated (COMPLETED → ACTIVE), re-enforcing the free-tier limit
     * on reactivation.
     *
     * 409 if already COMPLETED or if the project is ARCHIVED (must reactivate first).
     */
    @PostMapping("/{projectId}/complete")
    public ResponseEntity<ApiResponse<ProjectResponse>> completeProject(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID projectId
    ) {
        ProjectResponse project = projectService.completeProject(currentUserId, projectId);
        return ResponseEntity.ok(ApiResponse.ok("Project completed", project));
    }

    @PostMapping("/{projectId}/reactivate")
    public ResponseEntity<ApiResponse<ProjectResponse>> reactivateProject(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID projectId
    ) {
        ProjectResponse project = projectService.reactivateProject(currentUserId, projectId);
        return ResponseEntity.ok(ApiResponse.ok("Project reactivated", project));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID projectId
    ) {
        projectService.deleteProject(currentUserId, projectId);
        return ResponseEntity.noContent().build();
    }
}