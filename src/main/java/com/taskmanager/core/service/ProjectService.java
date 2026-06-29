package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.CreateProjectRequest;
import com.taskmanager.core.dto.request.UpdateProjectRequest;
import com.taskmanager.core.dto.response.ProjectResponse;
import com.taskmanager.core.enums.ProjectStatus;

import java.util.List;
import java.util.UUID;

public interface ProjectService {

    ProjectResponse createProject(UUID ownerId, CreateProjectRequest request);

    List<ProjectResponse> getProjects(UUID ownerId, ProjectStatus status);

    ProjectResponse getProjectById(UUID ownerId, UUID projectId);

    ProjectResponse updateProject(UUID ownerId, UUID projectId, UpdateProjectRequest request);

    ProjectResponse archiveProject(UUID ownerId, UUID projectId);

    /**
     * Transitions a project from ACTIVE to COMPLETED.
     * COMPLETED is a terminal-ish state meaning "all work is done" —
     * it can still be reactivated (re-enforcing the free-tier limit)
     * or archived for cleanup.
     *
     * Previously ProjectStatus.COMPLETED existed in the enum but had no
     * reachable transition anywhere in the codebase — dead code.
     */
    ProjectResponse completeProject(UUID ownerId, UUID projectId);

    ProjectResponse reactivateProject(UUID ownerId, UUID projectId);

    void deleteProject(UUID ownerId, UUID projectId);
}