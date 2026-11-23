package com.taskmanager.core;

import com.taskmanager.core.entity.Project;
import com.taskmanager.core.entity.Task;
import com.taskmanager.core.entity.Team;
import com.taskmanager.core.entity.TeamMember;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.security.JwtService;
import io.restassured.http.ContentType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;

import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TeamWebSocketAccessTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void removalClosesBothMemberConnectionsAndRetryLeavesNewConnectionOpen() throws Exception {
        Teams teams = createTeams();
        String ownerToken = jwtService.generateAccessToken(teams.ownerId());
        String memberToken = jwtService.generateAccessToken(teams.memberId());
        String removedTeamTopic = taskTopic(teams.removedTeamId());
        String remainingTeamTopic = taskTopic(teams.remainingTeamId());

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
             StompConnection upgradeAuthenticated = connect(client, memberToken, null);
             StompConnection stompAuthenticated = connect(client, null, memberToken);
             StompConnection owner = connect(client, null, ownerToken)) {
            upgradeAuthenticated.subscribe(removedTeamTopic);
            stompAuthenticated.subscribe(removedTeamTopic);
            owner.subscribe(removedTeamTopic);
            awaitSubscriptions(removedTeamTopic, 3);

            removeMember(teams, ownerToken);
            CloseStatus expected = CloseStatus.POLICY_VIOLATION.withReason("Team membership changed");
            assertThat(upgradeAuthenticated.closed.get(TIMEOUT.toSeconds(), SECONDS)).isEqualTo(expected);
            assertThat(stompAuthenticated.closed.get(TIMEOUT.toSeconds(), SECONDS)).isEqualTo(expected);
            assertThat(entityManager.find(Task.class, teams.removedTeamTaskId()).getAssignee()).isNull();

            updateTask(ownerToken, teams.removedTeamTaskId(), "Update for owner");
            assertThat(owner.nextFrame()).startsWith("MESSAGE\n").contains("TASK_UPDATED", "Update for owner");

            try (StompConnection reconnected = connect(client, null, memberToken)) {
                reconnected.subscribe(remainingTeamTopic);
                awaitSubscriptions(remainingTeamTopic, 1);

                removeMember(teams, ownerToken);
                updateTask(ownerToken, teams.remainingTeamTaskId(), "Update after retry");
                assertThat(reconnected.nextFrame())
                        .startsWith("MESSAGE\n").contains("TASK_UPDATED", "Update after retry");
                assertThat(reconnected.closed).isNotDone();
            }
        }
    }

    private Teams createTeams() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            User owner = new User("Owner", "owner-" + UUID.randomUUID() + "@example.com", "unused-hash");
            User member = new User("Member", "member-" + UUID.randomUUID() + "@example.com", "unused-hash");
            entityManager.persist(owner);
            entityManager.persist(member);
            Task removedTeamTask = createTeamTask("First team", owner, member);
            Task remainingTeamTask = createTeamTask("Second team", owner, member);
            return new Teams(owner.getId(), member.getId(),
                    removedTeamTask.getProject().getTeam().getId(), removedTeamTask.getId(),
                    remainingTeamTask.getProject().getTeam().getId(), remainingTeamTask.getId());
        });
    }

    private Task createTeamTask(String teamName, User owner, User member) {
        Team team = new Team(teamName, owner);
        entityManager.persist(team);
        entityManager.persist(new TeamMember(team, owner));
        entityManager.persist(new TeamMember(team, member));
        Project project = new Project("Project", null, team, owner);
        entityManager.persist(project);
        Task task = new Task("Original title", null, owner, project);
        task.setAssignee(member);
        entityManager.persist(task);
        return task;
    }

    private void removeMember(Teams teams, String ownerToken) {
        given().port(port).auth().oauth2(ownerToken)
                .delete("/api/v1/teams/{teamId}/members/{userId}", teams.removedTeamId(), teams.memberId())
                .then().statusCode(200);
    }

    private void updateTask(String token, UUID taskId, String title) {
        long version = entityManager.find(Task.class, taskId).getVersion();
        given().port(port).auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("title", title, "version", version))
                .patch("/api/v1/tasks/{taskId}", taskId).then().statusCode(200);
    }

    private String taskTopic(UUID teamId) {
        return "/topic/teams/" + teamId + "/tasks";
    }

    private void awaitSubscriptions(String destination, int count) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        await().atMost(TIMEOUT).untilAsserted(
                () -> assertThat(broker.getSubscriptionRegistry().findSubscriptions(message)).hasSize(count));
    }

    private StompConnection connect(HttpClient client, String upgradeToken, String stompToken) throws Exception {
        StompConnection connection = new StompConnection();
        WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(TIMEOUT).subprotocols("v12.stomp");
        if (upgradeToken != null) {
            builder.header("Authorization", "Bearer " + upgradeToken);
        }
        connection.socket = builder.buildAsync(URI.create("ws://localhost:" + port + "/ws"), connection)
                .get(TIMEOUT.toSeconds(), SECONDS);
        try {
            String frame = "CONNECT\naccept-version:1.2\nhost:localhost\n";
            if (stompToken != null) {
                frame += "Authorization:Bearer " + stompToken + "\n";
            }
            connection.send(frame + "\n");
            assertThat(connection.nextFrame()).startsWith("CONNECTED\n");
            return connection;
        } catch (Exception | AssertionError e) {
            connection.close();
            throw e;
        }
    }

    private record Teams(UUID ownerId, UUID memberId, UUID removedTeamId, UUID removedTeamTaskId,
                         UUID remainingTeamId, UUID remainingTeamTaskId) {}

    private static class StompConnection implements WebSocket.Listener, AutoCloseable {
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        private final StringBuilder fragments = new StringBuilder();
        private WebSocket socket;

        void send(String frame) throws Exception {
            socket.sendText(frame + '\0', true).get(TIMEOUT.toSeconds(), SECONDS);
        }

        void subscribe(String destination) throws Exception {
            send("SUBSCRIBE\nid:tasks\ndestination:" + destination + "\n\n");
        }

        String nextFrame() throws InterruptedException {
            return frames.poll(TIMEOUT.toSeconds(), SECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                frames.add(fragments.toString());
                fragments.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(new CloseStatus(statusCode, reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }

        @Override
        public void close() {
            socket.abort();
        }
    }
}
