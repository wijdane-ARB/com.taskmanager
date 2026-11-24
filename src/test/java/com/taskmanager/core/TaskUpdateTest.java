package com.taskmanager.core;

import com.taskmanager.core.dto.response.TaskResponse;
import com.taskmanager.core.entity.Project;
import com.taskmanager.core.entity.Task;
import com.taskmanager.core.entity.Team;
import com.taskmanager.core.entity.TeamMember;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.enums.TaskStatus;
import com.taskmanager.core.security.JwtService;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.restassured.path.json.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:task-updates",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class TaskUpdateTest {

    @Autowired
    private WebApplicationContext webContext;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private LettuceBasedProxyManager<byte[]> proxyManager;

    @MockitoBean(name = "brokerMessagingTemplate")
    private SimpMessagingTemplate messagingTemplate;

    @Test
    void consecutiveEditsReturnAndBroadcastTheirSavedVersions() throws Exception {
        Task task = createTask(null);
        UUID taskId = task.getId();
        String token = jwtService.generateAccessToken(task.getCreator().getId());
        MockMvc mvc = webAppContextSetup(webContext).apply(springSecurity()).build();

        long firstVersion = editTitle(mvc, token, taskId, "First edit", task.getVersion());
        long firstSavedVersion = entityManager.find(Task.class, taskId).getVersion();
        long secondVersion = editTitle(mvc, token, taskId, "Second edit", firstVersion);
        long secondSavedVersion = entityManager.find(Task.class, taskId).getVersion();

        assertThat(firstVersion).isEqualTo(firstSavedVersion).isGreaterThan(task.getVersion());
        assertThat(secondVersion).isEqualTo(secondSavedVersion).isGreaterThan(firstVersion);

        String destination = "/topic/teams/" + task.getProject().getTeam().getId() + "/tasks";
        ArgumentCaptor<Object> messages = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSend(
                eq(destination), messages.capture(), ArgumentMatchers.<Map<String, Object>>isNull());
        assertThat(messages.getAllValues())
                .extracting(message -> (TaskResponse) ((Map<?, ?>) message).get("task"))
                .extracting(TaskResponse::version)
                .containsExactly(firstVersion, secondVersion);
    }

    @Test
    void patchReopenCancelsOldOccurrenceBeforeCompletingAgain() throws Exception {
        Task task = createTask("DAILY");
        UUID taskId = task.getId();
        UUID groupId = task.getRecurrenceGroupId();
        String token = jwtService.generateAccessToken(task.getCreator().getId());
        MockMvc mvc = webAppContextSetup(webContext).apply(springSecurity()).build();

        completeTask(mvc, token, taskId);
        List<UUID> firstOccurrences = pendingOccurrenceIds(groupId);
        assertThat(firstOccurrences).hasSize(1);
        UUID oldOccurrenceId = firstOccurrences.getFirst();
        long completedVersion = entityManager.find(Task.class, taskId).getVersion();

        mvc.perform(patch("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"REOPENED","version":%d}
                                """.formatted(completedVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REOPENED"));

        assertThat(entityManager.find(Task.class, oldOccurrenceId).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(pendingOccurrenceIds(groupId)).isEmpty();

        completeTask(mvc, token, taskId);
        assertThat(pendingOccurrenceIds(groupId)).hasSize(1).doesNotContain(oldOccurrenceId);
    }

    @Test
    void githubUrlCanBeSetReplacedPreservedAndCleared() throws Exception {
        Task task = createTask(null);
        MockMvc mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        String issue = "https://github.com/octocat/example/issues/7#issuecomment-123";
        String pullRequest = "https://github.com/octocat/example/pull/8";

        long version = responseVersion(patchTask(mvc, task,
                """
                {"version":%d,"githubUrl":"%s"}
                """.formatted(task.getVersion(), issue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.githubUrl").value(issue)));
        assertThat(entityManager.find(Task.class, task.getId()).getGithubUrl()).isEqualTo(issue);

        version = responseVersion(patchTask(mvc, task,
                """
                {"version":%d,"githubUrl":"%s"}
                """.formatted(version, pullRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.githubUrl").value(pullRequest)));

        version = responseVersion(patchTask(mvc, task,
                """
                {"version":%d,"title":"Unrelated edit"}
                """.formatted(version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.githubUrl").value(pullRequest)));
        assertThat(entityManager.find(Task.class, task.getId()).getGithubUrl()).isEqualTo(pullRequest);

        patchTask(mvc, task,
                """
                {"version":%d,"githubUrl":null}
                """.formatted(version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.githubUrl").hasJsonPath())
                .andExpect(jsonPath("$.data.githubUrl").value(nullValue()));
        assertThat(entityManager.find(Task.class, task.getId()).getGithubUrl()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"http://github.com/octocat/example/issues/7\"",
            "\"https://github.com.evil.example/octocat/example/issues/7\"",
            "\"https://github.com/octocat/example/discussions/7\"",
            "\"https://github.com/octocat/example/pull/not-a-number\"",
            "7"
    })
    void rejectsInvalidGithubUrls(String linkJson) throws Exception {
        Task task = createTask(null);
        MockMvc mvc = webAppContextSetup(webContext).apply(springSecurity()).build();

        patchTask(mvc, task,
                """
                {"version":%d,"githubUrl":%s}
                """.formatted(task.getVersion(), linkJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("githubUrl must be an HTTPS GitHub issue or pull request URL."));
        assertThat(entityManager.find(Task.class, task.getId()).getVersion()).isEqualTo(task.getVersion());
    }

    private ResultActions patchTask(MockMvc mvc, Task task, String body) throws Exception {
        String token = jwtService.generateAccessToken(task.getCreator().getId());
        return mvc.perform(patch("/api/v1/tasks/{taskId}", task.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long responseVersion(ResultActions response) throws Exception {
        return JsonPath.from(response.andReturn().getResponse().getContentAsString()).getLong("data.version");
    }

    private List<UUID> pendingOccurrenceIds(UUID groupId) {
        return entityManager.createQuery("""
                        select t.id from Task t where t.recurrenceGroupId = :groupId and t.status = :status
                        """, UUID.class)
                .setParameter("groupId", groupId)
                .setParameter("status", TaskStatus.PENDING)
                .getResultList();
    }

    private void completeTask(MockMvc mvc, String token, UUID taskId) throws Exception {
        mvc.perform(post("/api/v1/tasks/{taskId}/complete", taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    private Task createTask(String recurrenceRule) {
        return new TransactionTemplate(transactionManager).execute(transaction -> {
            User owner = new User("Task Owner", "task-owner-" + UUID.randomUUID() + "@example.com", "unused-password-hash");
            entityManager.persist(owner);
            Team team = new Team("Task Team", owner);
            entityManager.persist(team);
            entityManager.persist(new TeamMember(team, owner));
            Project project = new Project("Task Project", null, team, owner);
            entityManager.persist(project);
            Task task = new Task("Original title", null, owner, project);
            if (recurrenceRule != null) {
                task.setDueDate(Instant.now().plus(1, ChronoUnit.DAYS));
                task.setRecurring(true);
                task.setRecurrenceRule(recurrenceRule);
                task.setRecurrenceGroupId(UUID.randomUUID());
            }
            entityManager.persist(task);
            return task;
        });
    }

    private long editTitle(MockMvc mvc, String token, UUID taskId, String title, long version) throws Exception {
        String response = mvc.perform(patch("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","version":%d}
                                """.formatted(title, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(title))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.from(response).getLong("data.version");
    }
}
