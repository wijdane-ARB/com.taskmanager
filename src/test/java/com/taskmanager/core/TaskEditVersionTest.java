package com.taskmanager.core;

import com.taskmanager.core.dto.response.TaskResponse;
import com.taskmanager.core.entity.Project;
import com.taskmanager.core.entity.Task;
import com.taskmanager.core.entity.Team;
import com.taskmanager.core.entity.TeamMember;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.security.JwtService;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.restassured.path.json.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:task-edit-version",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class TaskEditVersionTest {

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
        Task task = createTask();
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

    private Task createTask() {
        return new TransactionTemplate(transactionManager).execute(transaction -> {
            User owner = new User("Task Owner", "task-owner@example.com", "unused-password-hash");
            entityManager.persist(owner);
            Team team = new Team("Task Team", owner);
            entityManager.persist(team);
            entityManager.persist(new TeamMember(team, owner));
            Project project = new Project("Task Project", null, team, owner);
            entityManager.persist(project);
            Task task = new Task("Original title", null, owner, project);
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
