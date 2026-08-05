package com.taskmanager.core.service.impl;

import com.taskmanager.core.dto.request.CreateProjectRequest;
import com.taskmanager.core.dto.request.UpdateProjectRequest;
import com.taskmanager.core.dto.response.ProjectResponse;
import com.taskmanager.core.entity.Project;
import com.taskmanager.core.entity.Team;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.enums.ProjectStatus;
import com.taskmanager.core.enums.TaskStatus;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.repository.ProjectRepository;
import com.taskmanager.core.repository.TaskRepository;
import com.taskmanager.core.repository.TeamRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.service.ProjectService;
import com.taskmanager.core.service.TeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProjectServiceImpl implements ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectServiceImpl.class);
    private static final int FREE_TIER_PROJECT_LIMIT = 5;
    private static final List<TaskStatus> TERMINAL_STATUSES =
            List.of(TaskStatus.COMPLETED, TaskStatus.CANCELLED);

    private final ProjectRepository projectRepository;
    private final TaskRepository    taskRepository;
    private final UserRepository    userRepository;
    private final TeamRepository    teamRepository;
    private final TeamService       teamService;

    public ProjectServiceImpl(
            ProjectRepository projectRepository,
            TaskRepository taskRepository,
            UserRepository userRepository,
            TeamRepository teamRepository,
            TeamService teamService
    ) {
        this.projectRepository = projectRepository;
        this.taskRepository    = taskRepository;
        this.userRepository    = userRepository;
        this.teamRepository    = teamRepository;
        this.teamService       = teamService;
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ProjectResponse createProject(UUID callerId, CreateProjectRequest request) {
        UUID teamId = request.teamId();
        log.debug("Creating project in team {} for user {}", teamId, callerId);

        // Same enumeration-safe pattern as before: check access before even
        // confirming the team exists, and use one generic "not found" for both.
        if (!teamService.hasTeamAccess(teamId, callerId)) {
            throw new ResourceNotFoundException("Team not found.");
        }

        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found."));
        User createdBy = findUserOrThrow(callerId);

        if (!team.getOwner().isProSubscriber()) {
            long activeCount = projectRepository.countByTeamIdAndStatus(teamId, ProjectStatus.ACTIVE);
            if (activeCount >= FREE_TIER_PROJECT_LIMIT) {
                throw new IllegalStateException(
                        "Free tier allows up to " + FREE_TIER_PROJECT_LIMIT +
                                " active projects per team. Archive an existing project or upgrade to Pro."
                );
            }
        }

        Project project = new Project(request.name(), request.description(), team, createdBy);
        if (request.color() != null) project.setColor(request.color());

        Project saved = projectRepository.saveAndFlush(project);
        log.info("Project {} created in team {} by user {}", saved.getId(), teamId, callerId);

        return ProjectResponse.from(saved, 0L);
    }

    // ── Read ──────────────────────────────────────────────────────────────

    @Override
    public List<ProjectResponse> getProjects(UUID callerId, UUID teamId, ProjectStatus status) {
        if (!teamService.hasTeamAccess(teamId, callerId)) {
            throw new ResourceNotFoundException("Team not found.");
        }

        List<Project> projects =
                projectRepository.findByTeamIdAndStatusOrderByCreatedAtDesc(teamId, status);

        if (projects.isEmpty()) return List.of();

        Map<UUID, Long> countMap =
                projectRepository.activeTaskCountMapForTeam(teamId, TERMINAL_STATUSES);

        return projects.stream()
                .map(p -> ProjectResponse.from(p, countMap.getOrDefault(p.getId(), 0L)))
                .toList();
    }

    @Override
    public ProjectResponse getProjectById(UUID callerId, UUID projectId) {
        Project project = findProjectOrThrow(callerId, projectId);
        return ProjectResponse.from(project, activeTaskCount(projectId));
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ProjectResponse updateProject(UUID callerId, UUID projectId, UpdateProjectRequest request) {
        Project project = findProjectOrThrow(callerId, projectId);

        if (request.name() != null)        project.setName(request.name());
        if (request.description() != null) project.setDescription(request.description());
        if (request.color() != null)       project.setColor(request.color());

        Project saved = projectRepository.save(project);
        return ProjectResponse.from(saved, activeTaskCount(projectId));
    }

    // ── Status Transitions ───────────────────────────────────────────────

    @Override
    @Transactional
    public ProjectResponse archiveProject(UUID callerId, UUID projectId) {
        Project project = findProjectOrThrow(callerId, projectId);

        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new IllegalStateException("Project is already archived.");
        }
        project.setStatus(ProjectStatus.ARCHIVED);
        Project saved = projectRepository.save(project);

        log.info("Project {} archived by user {}", projectId, callerId);
        return ProjectResponse.from(saved, activeTaskCount(projectId));
    }

    @Override
    @Transactional
    public ProjectResponse completeProject(UUID callerId, UUID projectId) {
        Project project = findProjectOrThrow(callerId, projectId);

        if (project.getStatus() == ProjectStatus.COMPLETED) {
            throw new IllegalStateException("Project is already completed.");
        }
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new IllegalStateException("Cannot complete an archived project. Reactivate it first.");
        }

        project.setStatus(ProjectStatus.COMPLETED);
        Project saved = projectRepository.save(project);

        log.info("Project {} marked completed by user {}", projectId, callerId);
        return ProjectResponse.from(saved, activeTaskCount(projectId));
    }

    @Override
    @Transactional
    public ProjectResponse reactivateProject(UUID callerId, UUID projectId) {
        Project project = findProjectOrThrow(callerId, projectId);
        UUID teamId = project.getTeam().getId();

        if (project.getStatus() == ProjectStatus.ACTIVE) {
            throw new IllegalStateException("Project is already active.");
        }

        Team team = project.getTeam();
        if (!team.getOwner().isProSubscriber()) {
            long activeCount = projectRepository.countByTeamIdAndStatus(teamId, ProjectStatus.ACTIVE);
            if (activeCount >= FREE_TIER_PROJECT_LIMIT) {
                throw new IllegalStateException(
                        "Cannot reactivate: this team already has " + FREE_TIER_PROJECT_LIMIT +
                                " active projects. Archive one first or upgrade to Pro."
                );
            }
        }

        project.setStatus(ProjectStatus.ACTIVE);
        Project saved = projectRepository.save(project);

        log.info("Project {} reactivated by user {}", projectId, callerId);
        return ProjectResponse.from(saved, activeTaskCount(projectId));
    }

    // ── Delete ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deleteProject(UUID callerId, UUID projectId) {
        Project project = findProjectOrThrow(callerId, projectId);
        projectRepository.delete(project);
        log.info("Project {} and all its tasks deleted by user {}", projectId, callerId);
    }

    // ── Private Helpers ──────────────────────────────────────────────────

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    /**
     * Single lookup that collapses "doesn't exist" and "exists but you're
     * not on its team" into the same 404 — prevents UUID enumeration.
     * Every read/write path in this class goes through here; there is no
     * other place a project is fetched by ID.
     */
    private Project findProjectOrThrow(UUID callerId, UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found."));

        if (!teamService.hasTeamAccess(project.getTeam().getId(), callerId)) {
            throw new ResourceNotFoundException("Project not found.");
        }

        return project;
    }

    private long activeTaskCount(UUID projectId) {
        return taskRepository.countByProjectIdAndStatusNotIn(projectId, TERMINAL_STATUSES);
    }
}