package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.CreateProjectRequest;
import com.taskmanager.core.dto.response.ProjectResponse;
import com.taskmanager.core.entity.Project;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.enums.ProjectStatus;
import com.taskmanager.core.enums.TaskStatus;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.repository.ProjectRepository;
import com.taskmanager.core.repository.TaskRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.service.impl.ProjectServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectServiceImpl")
class ProjectServiceImplTest {

    @Mock ProjectRepository projectRepository;
    @Mock TaskRepository    taskRepository;
    @Mock UserRepository    userRepository;

    @InjectMocks ProjectServiceImpl projectService;

    private UUID    ownerId;
    private UUID    projectId;
    private User    owner;
    private Project project;

    private static final List<TaskStatus> TERMINAL =
            List.of(TaskStatus.COMPLETED, TaskStatus.CANCELLED);

    @BeforeEach
    void setUp() {
        ownerId   = UUID.randomUUID();
        projectId = UUID.randomUUID();
        owner     = new User("Test", "test@example.com", "hash");
        ReflectionTestUtils.setField(owner, "id", ownerId);

        project = new Project("Test Project", null, owner);
        ReflectionTestUtils.setField(project, "id", projectId);
    }

    private void stubUser()    { when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner)); }
    private void stubProject() { when(projectRepository.findByIdAndOwnerId(projectId, ownerId)).thenReturn(Optional.of(project)); }
    private void stubSave()    { when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0)); }
    private void stubCount(long count) {
        when(taskRepository.countByProjectIdAndStatusNotIn(eq(projectId), any())).thenReturn(count);
    }

    // ── createProject ─────────────────────────────────────────────────────────

    @Nested @DisplayName("createProject()")
    class CreateProject {

        @Test
        @DisplayName("creates project when user is under the free-tier limit")
        void underLimit() {
            stubUser();
            when(projectRepository.countByOwnerIdAndStatus(ownerId, ProjectStatus.ACTIVE))
                    .thenReturn(3L);
            when(projectRepository.save(any())).thenReturn(project);

            ProjectResponse resp = projectService.createProject(ownerId,
                    new CreateProjectRequest("New", null, null));

            assertThat(resp.name()).isEqualTo("Test Project");
        }

        @Test
        @DisplayName("throws at exactly the free-tier limit (5 active)")
        void atLimit() {
            stubUser();
            when(projectRepository.countByOwnerIdAndStatus(ownerId, ProjectStatus.ACTIVE))
                    .thenReturn(5L);

            assertThatThrownBy(() -> projectService.createProject(ownerId,
                    new CreateProjectRequest("Overflow", null, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("5");

            verify(projectRepository, never()).save(any());
        }

        @Test
        @DisplayName("Pro subscribers bypass the free-tier project limit")
        void proUserBypassesLimit() {
            ReflectionTestUtils.setField(owner, "isProSubscriber", true);
            stubUser();
            when(projectRepository.save(any())).thenReturn(project);

            // Should NOT throw even with 10 active projects
            projectService.createProject(ownerId,
                    new CreateProjectRequest("Pro Project", null, null));

            verify(projectRepository, never()).countByOwnerIdAndStatus(any(), any());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user does not exist")
        void userNotFound() {
            when(userRepository.findById(ownerId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> projectService.createProject(ownerId,
                    new CreateProjectRequest("X", null, null)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── getProjects ───────────────────────────────────────────────────────────

    @Nested @DisplayName("getProjects()")
    class GetProjects {

        @Test
        @DisplayName("uses bulk count query — never calls countByProjectIdAndStatusNotIn per-project")
        void usesBulkCountQuery() {
            // This test verifies the N+1 fix: the old code called
            // taskRepository.countByProjectIdAndStatusNotIn() once per project.
            // The fixed code calls projectRepository.activeTaskCountMapForOwner() once.
            Project p1 = new Project("P1", null, owner);
            Project p2 = new Project("P2", null, owner);
            ReflectionTestUtils.setField(p1, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(p2, "id", UUID.randomUUID());

            when(projectRepository.findByOwnerIdAndStatusOrderByCreatedAtDesc(ownerId, ProjectStatus.ACTIVE))
                    .thenReturn(List.of(p1, p2));
            when(projectRepository.activeTaskCountMapForOwner(eq(ownerId), any()))
                    .thenReturn(Map.of(p1.getId(), 3L, p2.getId(), 7L));

            List<ProjectResponse> results = projectService.getProjects(ownerId, ProjectStatus.ACTIVE);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).activeTaskCount()).isEqualTo(3L);
            assertThat(results.get(1).activeTaskCount()).isEqualTo(7L);

            // The per-project COUNT must never be called
            verify(taskRepository, never()).countByProjectIdAndStatusNotIn(any(), any());
            // The bulk count must be called exactly once regardless of project count
            verify(projectRepository, times(1)).activeTaskCountMapForOwner(any(), any());
        }

        @Test
        @DisplayName("returns empty list without hitting the count query")
        void emptyList() {
            when(projectRepository.findByOwnerIdAndStatusOrderByCreatedAtDesc(ownerId, ProjectStatus.ACTIVE))
                    .thenReturn(List.of());

            List<ProjectResponse> results = projectService.getProjects(ownerId, ProjectStatus.ACTIVE);

            assertThat(results).isEmpty();
            verify(projectRepository, never()).activeTaskCountMapForOwner(any(), any());
        }
    }

    // ── archiveProject ────────────────────────────────────────────────────────

    @Nested @DisplayName("archiveProject()")
    class ArchiveProject {

        @Test
        @DisplayName("transitions ACTIVE project to ARCHIVED")
        void archives() {
            ReflectionTestUtils.setField(project, "status", ProjectStatus.ACTIVE);
            stubProject();
            stubSave();
            stubCount(0L);

            ProjectResponse resp = projectService.archiveProject(ownerId, projectId);

            assertThat(resp.status()).isEqualTo(ProjectStatus.ARCHIVED);
        }

        @Test
        @DisplayName("throws when project is already ARCHIVED")
        void alreadyArchived() {
            ReflectionTestUtils.setField(project, "status", ProjectStatus.ARCHIVED);
            stubProject();

            assertThatThrownBy(() -> projectService.archiveProject(ownerId, projectId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already archived");
        }
    }

    // ── reactivateProject ─────────────────────────────────────────────────────

    @Nested @DisplayName("reactivateProject()")
    class ReactivateProject {

        @Test
        @DisplayName("reactivates an ARCHIVED project within the free-tier limit")
        void reactivates() {
            ReflectionTestUtils.setField(project, "status", ProjectStatus.ARCHIVED);
            stubProject();
            stubUser();
            when(projectRepository.countByOwnerIdAndStatus(ownerId, ProjectStatus.ACTIVE))
                    .thenReturn(4L);
            stubSave();
            stubCount(0L);

            ProjectResponse resp = projectService.reactivateProject(ownerId, projectId);

            assertThat(resp.status()).isEqualTo(ProjectStatus.ACTIVE);
        }

        @Test
        @DisplayName("throws when reactivation would exceed the free-tier limit")
        void reactivationBlockedAtLimit() {
            // Prevents "archive 4, create 5, reactivate to get 9 active" exploit
            ReflectionTestUtils.setField(project, "status", ProjectStatus.ARCHIVED);
            stubProject();
            stubUser();
            when(projectRepository.countByOwnerIdAndStatus(ownerId, ProjectStatus.ACTIVE))
                    .thenReturn(5L);

            assertThatThrownBy(() -> projectService.reactivateProject(ownerId, projectId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("5");

            verify(projectRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws when project is already ACTIVE")
        void alreadyActive() {
            ReflectionTestUtils.setField(project, "status", ProjectStatus.ACTIVE);
            stubProject();

            assertThatThrownBy(() -> projectService.reactivateProject(ownerId, projectId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already active");
        }
    }

    // ── deleteProject ─────────────────────────────────────────────────────────

    @Nested @DisplayName("deleteProject()")
    class DeleteProject {

        @Test
        @DisplayName("hard-deletes the project (tasks cascade at DB level)")
        void deletes() {
            stubProject();

            projectService.deleteProject(ownerId, projectId);

            verify(projectRepository).delete(project);
        }

        @Test
        @DisplayName("throws 404 for a project owned by a different user")
        void wrongOwner() {
            UUID otherOwner = UUID.randomUUID();
            when(projectRepository.findByIdAndOwnerId(projectId, otherOwner))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> projectService.deleteProject(otherOwner, projectId))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(projectRepository, never()).delete(any());
        }
    }
}
