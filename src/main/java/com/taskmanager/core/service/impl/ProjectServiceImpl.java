package com.taskmanager.core.service.impl;

import com.taskmanager.core.dto.request.CreateProjectRequest;
import com.taskmanager.core.dto.request.UpdateProjectRequest;
import com.taskmanager.core.dto.response.ProjectResponse;
import com.taskmanager.core.entity.Project;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.enums.ProjectStatus;
import com.taskmanager.core.enums.TaskStatus;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.repository.ProjectRepository;
import com.taskmanager.core.repository.TaskRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.service.ProjectService;
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

    public ProjectServiceImpl(
            ProjectRepository projectRepository,
            TaskRepository taskRepository,
            UserRepository userRepository
    ) {
        this.projectRepository = projectRepository;
        this.taskRepository    = taskRepository;
        this.userRepository    = userRepository;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ProjectResponse createProject(UUID ownerId, CreateProjectRequest request) {
        log.debug("Creating project for user {}", ownerId);

        User owner = findUserOrThrow(ownerId);

        if (!owner.isProSubscriber()) {
            long activeCount = projectRepository.countByOwnerIdAndStatus(ownerId, ProjectStatus.ACTIVE);
            if (activeCount >= FREE_TIER_PROJECT_LIMIT) {
                throw new IllegalStateException(
                        "Free tier allows up to " + FREE_TIER_PROJECT_LIMIT +
                                " active projects. Archive an existing project or upgrade to Pro."
                );
            }
        }

        Project project = new Project(request.name(), request.description(), owner);
        if (request.color() != null) {
            project.setColor(request.color());
        }

        Project saved = projectRepository.save(project);
        log.info("Project {} created for user {}", saved.getId(), ownerId);

        return ProjectResponse.from(saved, 0L);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    public List<ProjectResponse> getProjects(UUID ownerId, ProjectStatus status) {
        List<Project> projects =
                projectRepository.findByOwnerIdAndStatusOrderByCreatedAtDesc(ownerId, status);

        if (projects.isEmpty()) {
            return List.of();
        }

        /*
         * FIX — N+1 eliminated.
         *
         * Previously this called activeTaskCount(p.getId()) inside a stream(),
         * which issued one COUNT SQL query per project row returned (N+1).
         *
         * Now we fetch ALL active-task counts for this owner in ONE query,
         * build a Map<projectId, count>, then look up each project's count
         * in O(1). Total DB calls: 2 regardless of how many projects the
         * user has.
         */
        Map<UUID, Long> countMap =
                projectRepository.activeTaskCountMapForOwner(ownerId, TERMINAL_STATUSES);

        return projects.stream()
                .map(p -> ProjectResponse.from(p, countMap.getOrDefault(p.getId(), 0L)))
                .toList();
    }

    @Override
    public ProjectResponse getProjectById(UUID ownerId, UUID projectId) {
        Project project = findProjectOrThrow(ownerId, projectId);
        return ProjectResponse.from(project, activeTaskCount(projectId));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ProjectResponse updateProject(UUID ownerId, UUID projectId, UpdateProjectRequest request) {
        log.debug("Updating project {} for user {}", projectId, ownerId);

        Project project = findProjectOrThrow(ownerId, projectId);

        if (request.name() != null)        project.setName(request.name());
        if (request.description() != null) project.setDescription(request.description());
        if (request.color() != null)       project.setColor(request.color());

        Project saved = projectRepository.save(project);
        return ProjectResponse.from(saved, activeTaskCount(projectId));
    }

    // ── Status Transitions ────────────────────────────────────────────────────

    @Override
    @Transactional
    public ProjectResponse archiveProject(UUID ownerId, UUID projectId) {
        Project project = findProjectOrThrow(ownerId, projectId);

        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new IllegalStateException("Project is already archived.");
        }
        // COMPLETED projects can also be archived (funnel into ARCHIVED for uniform UX)
        project.setStatus(ProjectStatus.ARCHIVED);
        Project saved = projectRepository.save(project);

        log.info("Project {} archived by user {}", projectId, ownerId);
        return ProjectResponse.from(saved, activeTaskCount(projectId));
    }

    @Override
    @Transactional
    public ProjectResponse completeProject(UUID ownerId, UUID projectId) {
        Project project = findProjectOrThrow(ownerId, projectId);

        if (project.getStatus() == ProjectStatus.COMPLETED) {
            throw new IllegalStateException("Project is already completed.");
        }
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Cannot complete an archived project. Reactivate it first.");
        }

        project.setStatus(ProjectStatus.COMPLETED);
        Project saved = projectRepository.save(project);

        log.info("Project {} marked completed by user {}", projectId, ownerId);
        return ProjectResponse.from(saved, activeTaskCount(projectId));
    }

    @Override
    @Transactional
    public ProjectResponse reactivateProject(UUID ownerId, UUID projectId) {
        Project project = findProjectOrThrow(ownerId, projectId);

        if (project.getStatus() == ProjectStatus.ACTIVE) {
            throw new IllegalStateException("Project is already active.");
        }

        User owner = findUserOrThrow(ownerId);
        if (!owner.isProSubscriber()) {
            long activeCount = projectRepository.countByOwnerIdAndStatus(ownerId, ProjectStatus.ACTIVE);
            if (activeCount >= FREE_TIER_PROJECT_LIMIT) {
                throw new IllegalStateException(
                        "Cannot reactivate: you already have " + FREE_TIER_PROJECT_LIMIT +
                                " active projects. Archive one first or upgrade to Pro."
                );
            }
        }

        project.setStatus(ProjectStatus.ACTIVE);
        Project saved = projectRepository.save(project);

        log.info("Project {} reactivated by user {}", projectId, ownerId);
        return ProjectResponse.from(saved, activeTaskCount(projectId));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deleteProject(UUID ownerId, UUID projectId) {
        Project project = findProjectOrThrow(ownerId, projectId);
        projectRepository.delete(project);
        log.info("Project {} and all its tasks deleted by user {}", projectId, ownerId);
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private Project findProjectOrThrow(UUID ownerId, UUID projectId) {
        return projectRepository.findByIdAndOwnerId(projectId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found or does not belong to the current user"
                ));
    }

    private long activeTaskCount(UUID projectId) {
        return taskRepository.countByProjectIdAndStatusNotIn(projectId, TERMINAL_STATUSES);
    }
}