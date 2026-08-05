package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.CreateProjectRequest;
import com.taskmanager.core.dto.request.UpdateProjectRequest;
import com.taskmanager.core.dto.response.ProjectResponse;
import com.taskmanager.core.enums.ProjectStatus;

import java.util.List;
import java.util.UUID;

public interface ProjectService {

    ProjectResponse createProject(UUID callerId, CreateProjectRequest request);

    List<ProjectResponse> getProjects(UUID callerId, UUID teamId, ProjectStatus status);

    ProjectResponse getProjectById(UUID callerId, UUID projectId);

    ProjectResponse updateProject(UUID callerId, UUID projectId, UpdateProjectRequest request);

    ProjectResponse archiveProject(UUID callerId, UUID projectId);

    ProjectResponse completeProject(UUID callerId, UUID projectId);

    ProjectResponse reactivateProject(UUID callerId, UUID projectId);

    void deleteProject(UUID callerId, UUID projectId);
}