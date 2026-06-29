package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.UpdateTaskRequest;
import com.taskmanager.core.dto.response.TaskResponse;
import com.taskmanager.core.entity.Project;
import com.taskmanager.core.entity.Task;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.enums.PriorityLevel;
import com.taskmanager.core.enums.TaskStatus;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.repository.ProjectRepository;
import com.taskmanager.core.repository.TaskRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.service.impl.TaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskServiceImpl")
class TaskServiceImplTest {

    @Mock TaskRepository    taskRepository;
    @Mock ProjectRepository projectRepository;
    @Mock UserRepository    userRepository;

    @InjectMocks TaskServiceImpl taskService;

    private UUID    ownerId;
    private UUID    taskId;
    private User    owner;
    private Project project;

    @BeforeEach
    void setUp() {
        ownerId  = UUID.randomUUID();
        taskId   = UUID.randomUUID();
        owner    = new User("Test", "test@example.com", "hash");
        ReflectionTestUtils.setField(owner, "id", ownerId);

        project = new Project("My Project", null, owner);
        ReflectionTestUtils.setField(project, "id", UUID.randomUUID());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Task makeTask(TaskStatus status) {
        Task t = new Task("Do something", null, owner, project);
        ReflectionTestUtils.setField(t, "id", taskId);
        ReflectionTestUtils.setField(t, "status", status);
        return t;
    }

    private void stubFindTask(Task task) {
        when(taskRepository.findByIdAndOwnerIdAndStatusNot(taskId, ownerId, TaskStatus.CANCELLED))
                .thenReturn(Optional.of(task));
        lenient().when(taskRepository.save(any(Task.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ── completeTask ──────────────────────────────────────────────────────────

    @Nested @DisplayName("completeTask()")
    class CompleteTask {

        @Test
        @DisplayName("sets status to COMPLETED and records completedAt")
        void marksCompleted() {
            Task task = makeTask(TaskStatus.PENDING);
            stubFindTask(task);

            TaskResponse resp = taskService.completeTask(ownerId, taskId);

            assertThat(resp.status()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(resp.completedAt()).isNotNull();
        }

        @Test
        @DisplayName("is idempotent — completing an already-COMPLETED task is a no-op")
        void idempotent() {
            Task task = makeTask(TaskStatus.COMPLETED);
            ReflectionTestUtils.setField(task, "completedAt",
                    java.time.Instant.now().minusSeconds(60));
            stubFindTask(task);

            TaskResponse resp = taskService.completeTask(ownerId, taskId);

            assertThat(resp.status()).isEqualTo(TaskStatus.COMPLETED);
            // save should NOT be called — task was already complete
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when task not found")
        void notFound() {
            when(taskRepository.findByIdAndOwnerIdAndStatusNot(any(), any(), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.completeTask(ownerId, taskId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── reopenTask ────────────────────────────────────────────────────────────

    @Nested @DisplayName("reopenTask()")
    class ReopenTask {

        @Test
        @DisplayName("reopens a COMPLETED task and increments reopenedCount")
        void reopens() {
            Task task = makeTask(TaskStatus.COMPLETED);
            stubFindTask(task);

            TaskResponse resp = taskService.reopenTask(ownerId, taskId);

            assertThat(resp.status()).isEqualTo(TaskStatus.REOPENED);
            assertThat(resp.reopenedCount()).isEqualTo(1);
            assertThat(resp.completedAt()).isNull(); // cleared on reopen
        }

        @ParameterizedTest(name = "throws when status is {0}")
        @EnumSource(value = TaskStatus.class, names = {"PENDING", "IN_PROGRESS", "REOPENED"})
        @DisplayName("throws IllegalStateException when task is not COMPLETED")
        void throwsIfNotCompleted(TaskStatus status) {
            Task task = makeTask(status);
            stubFindTask(task);

            assertThatThrownBy(() -> taskService.reopenTask(ownerId, taskId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("COMPLETED");
        }
    }

    // ── recordSnooze ──────────────────────────────────────────────────────────

    @Nested @DisplayName("recordSnooze()")
    class RecordSnooze {

        @ParameterizedTest(name = "increments snoozedCount when status is {0}")
        @EnumSource(value = TaskStatus.class, names = {"PENDING", "IN_PROGRESS", "REOPENED"})
        @DisplayName("increments snoozedCount for active task statuses")
        void incrementsForActiveStatuses(TaskStatus status) {
            Task task = makeTask(status);
            stubFindTask(task);

            TaskResponse resp = taskService.recordSnooze(ownerId, taskId);

            assertThat(resp.snoozedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("INTEGRITY: throws when snoozing a COMPLETED task")
        void throwsOnCompleted() {
            // Snoozing a completed task would corrupt the RL training signal —
            // a stale push notification arriving after completion must be rejected.
            Task task = makeTask(TaskStatus.COMPLETED);
            stubFindTask(task);

            assertThatThrownBy(() -> taskService.recordSnooze(ownerId, taskId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("COMPLETED");
        }

        @Test
        @DisplayName("INTEGRITY: throws when snoozing a CANCELLED task")
        void throwsOnCancelled() {
            // CANCELLED tasks are normally invisible (findByIdAndOwnerIdAndStatusNot
            // excludes them), but test the domain guard directly.
            Task task = makeTask(TaskStatus.CANCELLED);
            stubFindTask(task);

            assertThatThrownBy(() -> taskService.recordSnooze(ownerId, taskId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CANCELLED");
        }
    }

    // ── deleteTask (soft delete) ──────────────────────────────────────────────

    @Nested @DisplayName("deleteTask()")
    class DeleteTask {

        @Test
        @DisplayName("sets status to CANCELLED rather than deleting the row")
        void softDelete() {
            Task task = makeTask(TaskStatus.PENDING);
            stubFindTask(task);

            TaskResponse resp = taskService.deleteTask(ownerId, taskId);

            assertThat(resp.status()).isEqualTo(TaskStatus.CANCELLED);
            // Must save, not delete — the row must stay for RL training data
            verify(taskRepository).save(any(Task.class));
            verify(taskRepository, never()).delete(any(Task.class));
            verify(taskRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("CANCELLED task is invisible to subsequent findTask calls")
        void cancelledTaskIsInvisible() {
            // After deletion, findByIdAndOwnerIdAndStatusNot(CANCELLED) returns empty —
            // simulate that and verify the service throws 404 (not exposing deleted data).
            when(taskRepository.findByIdAndOwnerIdAndStatusNot(taskId, ownerId, TaskStatus.CANCELLED))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.getTaskById(ownerId, taskId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── Status transition guard ───────────────────────────────────────────────

    @Nested @DisplayName("updateTask() — status transitions via applyStatusTransition")
    class StatusTransitions {

        private UpdateTaskRequest requestWithStatus(TaskStatus target) {
            return new UpdateTaskRequest(null, null, null, null, null,
                    target, null, null, null);
        }

        @Test
        @DisplayName("PENDING → IN_PROGRESS records actualStartTime")
        void pendingToInProgress() {
            Task task = makeTask(TaskStatus.PENDING);
            stubFindTask(task);

            TaskResponse resp = taskService.updateTask(ownerId, taskId,
                    requestWithStatus(TaskStatus.IN_PROGRESS));

            assertThat(resp.status()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(resp.actualStartTime()).isNotNull();
        }

        @Test
        @DisplayName("cannot cancel a COMPLETED task — must reopen first")
        void cannotCancelCompleted() {
            Task task = makeTask(TaskStatus.COMPLETED);
            stubFindTask(task);

            assertThatThrownBy(() ->
                    taskService.updateTask(ownerId, taskId, requestWithStatus(TaskStatus.CANCELLED)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Reopen");
        }

        @Test
        @DisplayName("cannot transition directly to PENDING")
        void cannotTransitionToPending() {
            Task task = makeTask(TaskStatus.IN_PROGRESS);
            stubFindTask(task);

            assertThatThrownBy(() ->
                    taskService.updateTask(ownerId, taskId, requestWithStatus(TaskStatus.PENDING)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PENDING");
        }
    }

    // ── Ownership guard ───────────────────────────────────────────────────────

    @Nested @DisplayName("Ownership enforcement")
    class Ownership {

        @Test
        @DisplayName("getTaskById throws 404 when task belongs to a different user")
        void cannotAccessOtherUsersTask() {
            UUID otherUser = UUID.randomUUID();
            when(taskRepository.findByIdAndOwnerIdAndStatusNot(taskId, otherUser, TaskStatus.CANCELLED))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.getTaskById(otherUser, taskId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("completeTask throws 404 when task belongs to a different user")
        void cannotCompleteOtherUsersTask() {
            UUID otherUser = UUID.randomUUID();
            when(taskRepository.findByIdAndOwnerIdAndStatusNot(taskId, otherUser, TaskStatus.CANCELLED))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.completeTask(otherUser, taskId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
