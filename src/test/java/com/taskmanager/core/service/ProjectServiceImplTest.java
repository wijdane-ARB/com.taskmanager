package com.taskmanager.core.service;

import com.taskmanager.core.entity.Project;
import com.taskmanager.core.entity.Team;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.exception.ForbiddenException;
import com.taskmanager.core.repository.ProjectRepository;
import com.taskmanager.core.repository.TaskRepository;
import com.taskmanager.core.repository.TeamRepository;
import com.taskmanager.core.service.impl.ProjectServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ProjectServiceImplTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TeamService teamService;

    @InjectMocks
    private ProjectServiceImpl projectService;

    @Test
    @DisplayName("Non-owner and non-creator cannot delete project")
    void testNonOwnerCannotDeleteProject() {
        UUID ownerId = UUID.randomUUID();
        UUID nonOwnerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();

        User owner = new User("Owner", "owner@example.com", "hash");
        owner.setId(ownerId);
        Team team = new Team("Team", owner);
        team.setId(teamId);

        User creator = new User("Creator", "creator@example.com", "hash");
        creator.setId(UUID.randomUUID());
        Project project = new Project("Project", "Desc", team, creator);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(teamService.hasTeamAccess(teamId, nonOwnerId)).thenReturn(true);

        assertThrows(ForbiddenException.class, () -> {
            projectService.deleteProject(nonOwnerId, projectId);
        });
    }
}