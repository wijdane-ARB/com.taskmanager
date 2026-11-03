package com.taskmanager.core;

import com.taskmanager.core.dto.request.*;
import com.taskmanager.core.dto.response.*;
import com.taskmanager.core.enums.PriorityLevel;
import com.taskmanager.core.enums.TaskStatus;
import com.taskmanager.core.enums.VerificationCodeType;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private io.lettuce.core.RedisClient redisClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Value("${app.security.code-hmac-secret}")
    private String codeHmacSecret;

    private static String user1Token;
    private static String user2Token;
    private static UUID user1Id;
    private static UUID team1Id;

    @BeforeEach
    public void setUp() throws Exception {
        RestAssured.port = port;
        try {
            redisClient.connect().sync().flushall();
        } catch (Exception ignored) {}
    }

    private String getRawCodeForUser(String email, VerificationCodeType type) {
        // Manually overwrite the hash in DB with the hash of "123456" for testing
        String testCode = "123456";
        String testHash;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(codeHmacSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            
            // We need userId to generate the hash. Use UUID.class to prevent Postgres type mapping errors.
            UUID userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
            String message = userId.toString() + ":" + type.name() + ":" + testCode;
            byte[] hash = mac.doFinal(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            testHash = sb.toString();
            
            jdbcTemplate.update("UPDATE verification_codes SET code = ? WHERE user_id = ? AND type = ?", testHash, userId, type.name());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return testCode;
    }

    @Test
    @Order(1)
    void authFlow_and_RegisterOverUnverified() {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");

        // 1. Register unverified user
        RegisterRequest reg1 = new RegisterRequest("User One", "user1@test.com", "Password@123!");
        given().contentType(ContentType.JSON).body(reg1).when().post("/api/v1/auth/register")
                .then().statusCode(201);
        
        // 2. Register over unverified email
        RegisterRequest reg2 = new RegisterRequest("User One Replaced", "user1@test.com", "NewPassword@123!");
        given().contentType(ContentType.JSON).body(reg2).when().post("/api/v1/auth/register")
                .then().statusCode(201);

        // 3. Verify
        String code = getRawCodeForUser("user1@test.com", VerificationCodeType.EMAIL_VERIFICATION);
        VerifyEmailRequest ver = new VerifyEmailRequest("user1@test.com", code);
        Response r3 = given().contentType(ContentType.JSON).body(ver).when().post("/api/v1/auth/verify-email");
        r3.then().statusCode(200);
        
        user1Token = r3.jsonPath().getString("data.accessToken");
        user1Id = UUID.fromString(r3.jsonPath().getString("data.userId"));
        String refreshToken1 = r3.jsonPath().getString("data.refreshToken");

        // Register user2
        given().contentType(ContentType.JSON).body(new RegisterRequest("User Two", "user2@test.com", "Password@123!")).when().post("/api/v1/auth/register").then().statusCode(201);
        String code2 = getRawCodeForUser("user2@test.com", VerificationCodeType.EMAIL_VERIFICATION);
        Response rUser2 = given().contentType(ContentType.JSON).body(new VerifyEmailRequest("user2@test.com", code2)).when().post("/api/v1/auth/verify-email");
        rUser2.then().statusCode(200);
        user2Token = rUser2.jsonPath().getString("data.accessToken");

        // 4. Refresh rotation
        Response r4 = given().contentType(ContentType.JSON).body(new RefreshRequest(refreshToken1)).when().post("/api/v1/auth/refresh");
        r4.then().statusCode(200);
        user1Token = r4.jsonPath().getString("data.accessToken");
        String newRefreshToken = r4.jsonPath().getString("data.refreshToken");

        // Bypass the 30-second "parallel network request" grace period
        // Otherwise, the reuse below will just throw 401 without wiping all sessions
        jdbcTemplate.update("UPDATE refresh_tokens SET updated_at = NOW() - INTERVAL '35 seconds' WHERE revoked = true");

        // 5. Reuse of revoked refresh token
        given().contentType(ContentType.JSON).body(new RefreshRequest(refreshToken1)).when().post("/api/v1/auth/refresh")
                .then().statusCode(401);

        // Even the new refresh token should be revoked (all sessions wiped out)
        given().contentType(ContentType.JSON).body(new RefreshRequest(newRefreshToken)).when().post("/api/v1/auth/refresh")
                .then().statusCode(401);

        // Log back in
        Response r7 = given().contentType(ContentType.JSON).body(new LoginRequest("user1@test.com", "NewPassword@123!")).when().post("/api/v1/auth/login");
        r7.then().statusCode(200);
        user1Token = r7.jsonPath().getString("data.accessToken");
    }

    @Test
    @Order(2)
    void rateLimiter_returns429On6thLogin() {
        LoginRequest login = new LoginRequest("wrong@test.com", "Wrong@123!");
        for (int i = 0; i < 5; i++) {
            given().contentType(ContentType.JSON).body(login).when().post("/api/v1/auth/login")
                    .then().statusCode(401);
        }
        // 6th attempt -> 429
        given().contentType(ContentType.JSON).body(login).when().post("/api/v1/auth/login")
                .then().statusCode(429);
    }

    @Test
    @Order(3)
    void taskAccessDeniedAcrossTeams_and_OptimisticLocking_and_StatusTransitions() {
        // Setup: User1 gets their team ID
        Response t1 = given().header("Authorization", "Bearer " + user1Token).when().get("/api/v1/teams");
        t1.then().statusCode(200);
        team1Id = UUID.fromString(t1.jsonPath().getString("data[0].id"));

        // Setup: Create a project
        Response p1 = given().header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body(Map.of("name", "Project 1", "teamId", team1Id.toString()))
                .when().post("/api/v1/projects");
        p1.then().statusCode(201);
        UUID projectId = UUID.fromString(p1.jsonPath().getString("data.id"));

        // 1. Create a task in team 1
        CreateTaskRequest createReq = new CreateTaskRequest("Task 1", "Desc", projectId, user1Id, null, null, PriorityLevel.NORMAL, null, false, null);
        Response taskRes = given().header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body(createReq)
                .when().post("/api/v1/tasks");
        taskRes.then().statusCode(201);
        UUID taskId = UUID.fromString(taskRes.jsonPath().getString("data.id"));
        Long v = taskRes.jsonPath().getLong("data.version");

        // 2. User 2 tries to access Task 1
        given().header("Authorization", "Bearer " + user2Token)
                .when().get("/api/v1/tasks/" + taskId)
                .then().statusCode(404);

        // 3. Status transitions
        UpdateTaskRequest completeReq = new UpdateTaskRequest(null, null, null, null, null, null, null, TaskStatus.COMPLETED, null, null, null, v);
        Response statRes1 = given().header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON).body(completeReq)
                .when().patch("/api/v1/tasks/" + taskId);
        statRes1.then().statusCode(200);
        v = statRes1.jsonPath().getLong("data.version");

        // Invalid: COMPLETED -> PENDING
        UpdateTaskRequest pendingReq = new UpdateTaskRequest(null, null, null, null, null, null, null, TaskStatus.PENDING, null, null, null, v);
        given().header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON).body(pendingReq)
                .when().patch("/api/v1/tasks/" + taskId)
                .then().statusCode(409); // Conflict (IllegalStateException mapped to 409)

        // Valid: COMPLETED -> REOPENED
        Response reopenRes = given().header("Authorization", "Bearer " + user1Token)
                .when().post("/api/v1/tasks/" + taskId + "/reopen");
        reopenRes.then().statusCode(200);
        v = reopenRes.jsonPath().getLong("data.version");

        // 4. Optimistic locking: PATCH with stale version -> 409
        UpdateTaskRequest staleReq = new UpdateTaskRequest("Stale Edit", null, null, null, null, null, null, null, null, null, null, v - 1);
        given().header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON).body(staleReq)
                .when().patch("/api/v1/tasks/" + taskId)
                .then().statusCode(409);
    }

    @Test
    @Order(4)
    void recurringTasks_and_CalendarReschedule_and_Attachments() {
        Response p1 = given().header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON)
                .body(Map.of("name", "Project 2", "teamId", team1Id.toString()))
                .when().post("/api/v1/projects");
        UUID projectId = UUID.fromString(p1.jsonPath().getString("data.id"));

        // 1. Recurring complete creates next, delete cancels group
        CreateTaskRequest recReq = new CreateTaskRequest("Rec Task", null, projectId, user1Id, Instant.now(), null, PriorityLevel.NORMAL, null, true, "DAILY");
        Response recRes = given().header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON).body(recReq)
                .when().post("/api/v1/tasks");
        recRes.then().statusCode(201);
        UUID recTaskId = UUID.fromString(recRes.jsonPath().getString("data.id"));
        
        given().header("Authorization", "Bearer " + user1Token)
                .when().post("/api/v1/tasks/" + recTaskId + "/complete")
                .then().statusCode(200);
        
        Response pageRes = given().header("Authorization", "Bearer " + user1Token)
                .when().get("/api/v1/tasks?projectId=" + projectId);
        pageRes.then().statusCode(200);
        List<Map<String,Object>> tasks = pageRes.jsonPath().getList("data.content");
        
        UUID nextTaskId = null;
        for (Map<String,Object> t : tasks) {
            if ("PENDING".equals(t.get("status"))) {
                nextTaskId = UUID.fromString(t.get("id").toString());
            }
        }
        assertThat(nextTaskId).isNotNull();

        // Delete cancels occurrences
        given().header("Authorization", "Bearer " + user1Token)
                .when().delete("/api/v1/tasks/" + nextTaskId)
                .then().statusCode(200);
        
        // 2. Calendar range & reschedule
        CreateTaskRequest calReq = new CreateTaskRequest("Cal Task", null, projectId, user1Id, Instant.parse("2026-10-15T10:00:00Z"), null, PriorityLevel.NORMAL, null, false, null);
        Response calRes = given().header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON).body(calReq)
                .when().post("/api/v1/tasks");
        UUID calTaskId = UUID.fromString(calRes.jsonPath().getString("data.id"));
        
        RescheduleTaskRequest reschReq = new RescheduleTaskRequest(Instant.parse("2026-10-16T10:00:00Z"), null, false);
        Response reschedTaskRes = given().header("Authorization", "Bearer " + user1Token)
                .contentType(ContentType.JSON).body(reschReq)
                .when().patch("/api/v1/tasks/" + calTaskId + "/reschedule");
        reschedTaskRes.then().statusCode(200);
        assertThat(reschedTaskRes.jsonPath().getInt("data.snoozedCount")).isEqualTo(0);

        Response calList = given().header("Authorization", "Bearer " + user1Token)
                .when().get("/api/v1/calendar?teamId=" + team1Id + "&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z");
        calList.then().statusCode(200);
        assertThat((List<?>) calList.jsonPath().getList("data")).isNotEmpty();

        // 3. Attachment upload, list, download, delete
        Response attRes = given().header("Authorization", "Bearer " + user1Token)
                .multiPart("file", "test.txt", "test content".getBytes(), "text/plain")
                .when().post("/api/v1/tasks/" + calTaskId + "/attachments");
        attRes.then().statusCode(200);

        Response listRes = given().header("Authorization", "Bearer " + user1Token)
                .when().get("/api/v1/tasks/" + calTaskId + "/attachments");
        listRes.then().statusCode(200);
        List<Map<String,Object>> attachments = listRes.jsonPath().getList("data");
        assertThat(attachments).hasSize(1);
        UUID attId = UUID.fromString(attachments.get(0).get("id").toString());

        Response dlRes = given().header("Authorization", "Bearer " + user1Token)
                .when().get("/api/v1/tasks/" + calTaskId + "/attachments/" + attId);
        dlRes.then().statusCode(200);
        assertThat(dlRes.getBody().asByteArray()).isEqualTo("test content".getBytes());

        given().header("Authorization", "Bearer " + user1Token)
                .when().delete("/api/v1/tasks/" + calTaskId + "/attachments/" + attId)
                .then().statusCode(200);
    }

    @Test
    @Order(5)
    void rateLimiter_payloadTooLarge() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9000; i++) sb.append("a");
        String largeBody = "{\"email\":\"toolarge@test.com\",\"password\":\"" + sb.toString() + "\"}";
        
        given().contentType(ContentType.JSON)
                .body(largeBody)
                .when().post("/api/v1/auth/login")
                .then().statusCode(413);
    }

    @Test
    @Order(6)
    void rateLimiter_alternateContentType() {
        String body = "{\"email\":\"x@test.com\"}";
        given().contentType("application/problem+json")
                .body(body)
                .when().post("/api/v1/auth/forgot-password")
                .then().statusCode(200);
                
        given().contentType("application/problem+json")
                .body(body)
                .when().post("/api/v1/auth/forgot-password")
                .then().statusCode(200);
                
        given().contentType("application/problem+json")
                .body(body)
                .when().post("/api/v1/auth/forgot-password")
                .then().statusCode(429);
    }
}