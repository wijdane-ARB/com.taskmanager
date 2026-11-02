package com.taskmanager.core.entity;

import com.taskmanager.core.enums.PriorityLevel;
import com.taskmanager.core.enums.TaskStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * The central entity of the entire platform. Every field here
 * serves either the core product UX or the RL training pipeline.
 *
 * ─── RL DATA FIELDS ────────────────────────────────────────────
 * The following behavioral fields are collected with explicit user consent
 * (pursuant to GDPR & Law 09-08 compliance) for ML model scheduling optimization:
 *
 *   scheduledTime         → What time the user *intended* to do this
 *   actualStartTime       → When they *actually* started (set on IN_PROGRESS)
 *   completedAt           → When they finished (set on COMPLETED)
 *   estimatedDurationMins → User's self-reported estimate
 *   reopenedCount         → How many times the task was un-completed
 *   snoozedCount          → How many times a reminder was dismissed
 *   dayOfWeekCreated      → Derived at creation time (1=Mon, 7=Sun)
 *
 * These feed the reward function:
 *   +1.0  if completedAt is before dueDate
 *   +0.5  if completedAt is after dueDate but task is done
 *   -1.0  per snoozedCount increment
 *   -0.5  per reopenedCount increment
 * ───────────────────────────────────────────────────────────────
 */
@Entity
@Table(
        name = "tasks",
        indexes = {
                @Index(name = "idx_tasks_project_id",  columnList = "project_id"),
                @Index(name = "idx_tasks_assignee_id",    columnList = "assignee_id"),
                @Index(name = "idx_tasks_status",      columnList = "status"),
                @Index(name = "idx_tasks_due_date",    columnList = "due_date"),
                @Index(name = "idx_tasks_assignee_status_due", columnList = "assignee_id, status, due_date")
        }
)
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank
    @Size(min = 1, max = 255)
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Size(max = 2000)
    @Column(name = "description", length = 2000)
    private String description;

    // ── Core scheduling fields ──────────────────────────────────

    @Column(name = "due_date")
    private Instant dueDate;

    @Column(name = "scheduled_time")
    private Instant scheduledTime;

    @Column(name = "is_recurring", nullable = false)
    private boolean isRecurring = false;

    @Column(name = "recurrence_rule", length = 100)
    private String recurrenceRule;

    @Column(name = "recurrence_group_id")
    private UUID recurrenceGroupId;

    // ── Status & Priority ───────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "priority_level", nullable = false)
    private PriorityLevel priorityLevel = PriorityLevel.NORMAL;

    // ── RL Behavioral Data Fields ───────────────────────────────

    @Column(name = "actual_start_time")
    private Instant actualStartTime;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Min(1)
    @Column(name = "estimated_duration_minutes")
    private Integer estimatedDurationMinutes;

    @Column(name = "reopened_count", nullable = false)
    private int reopenedCount = 0;

    @Column(name = "snoozed_count", nullable = false)
    private int snoozedCount = 0;

    // CHANGE 2: Javadoc corrected from "0=Mon, 6=Sun" to "1=Mon, 7=Sun"
    // to accurately reflect ISO-8601 and the DayOfWeek.getValue() contract
    // used in the constructor below.
    /**
     * Derived from createdAt at insert time. Stored denormalized to avoid
     * a computed column in every ML query.
     * 1 = Monday, 7 = Sunday (ISO-8601 / DayOfWeek.getValue() convention).
     */
    @Column(name = "day_of_week_created", nullable = false)
    private int dayOfWeekCreated;

    @Column(name = "hour_of_day_created", nullable = false)
    private int hourOfDayCreated;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<Attachment> attachments = new java.util.ArrayList<>();

    // ── Audit ───────────────────────────────────────────────────

    @Version
    @Column(name = "version")
    private Long version;

    @org.hibernate.annotations.CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── Relationships ───────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = true)
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // ── Constructors ────────────────────────────────────────────

    protected Task() {}

    public Task(String title, String description, User creator, Project project) {
        this.title = title;
        this.description = description;
        this.creator = creator;
        this.assignee = creator; // Default assignee to creator
        this.project = project;

        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
        this.dayOfWeekCreated = now.getDayOfWeek().getValue(); // 1=Mon, 7=Sun
        this.hourOfDayCreated = now.getHour();
    }

    // ── Domain Methods ──────────────────────────────────────────

    public void markAsStarted() {
        if (this.actualStartTime == null) {
            this.actualStartTime = Instant.now();
        }
        this.status = TaskStatus.IN_PROGRESS;
    }

    public void markAsCompleted() {
        if (this.status != TaskStatus.COMPLETED) {
            this.completedAt = Instant.now();
        }
        this.status = TaskStatus.COMPLETED;
    }

    public void markAsReopened() {
        this.status = TaskStatus.REOPENED;
        this.reopenedCount++;
        this.completedAt = null;
    }

    public void recordSnooze() {
        this.snoozedCount++;
    }

    // ── Getters & Setters ───────────────────────────────────────

    public UUID getId() { return id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getDueDate() { return dueDate; }
    public void setDueDate(Instant dueDate) { this.dueDate = dueDate; }

    public Instant getScheduledTime() { return scheduledTime; }
    public void setScheduledTime(Instant scheduledTime) { this.scheduledTime = scheduledTime; }

    public boolean isRecurring() { return isRecurring; }
    public void setRecurring(boolean recurring) { isRecurring = recurring; }

    public String getRecurrenceRule() { return recurrenceRule; }
    public void setRecurrenceRule(String recurrenceRule) { this.recurrenceRule = recurrenceRule; }

    public UUID getRecurrenceGroupId() { return recurrenceGroupId; }
    public void setRecurrenceGroupId(UUID recurrenceGroupId) { this.recurrenceGroupId = recurrenceGroupId; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public PriorityLevel getPriorityLevel() { return priorityLevel; }
    public void setPriorityLevel(PriorityLevel priorityLevel) { this.priorityLevel = priorityLevel; }

    public Instant getActualStartTime() { return actualStartTime; }
    public Instant getCompletedAt() { return completedAt; }

    public Integer getEstimatedDurationMinutes() { return estimatedDurationMinutes; }
    public void setEstimatedDurationMinutes(Integer estimatedDurationMinutes) {
        this.estimatedDurationMinutes = estimatedDurationMinutes;
    }

    public int getReopenedCount() { return reopenedCount; }
    public int getSnoozedCount() { return snoozedCount; }
    public int getDayOfWeekCreated() { return dayOfWeekCreated; }
    public int getHourOfDayCreated() { return hourOfDayCreated; }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public User getCreator() { return creator; }
    public void setCreator(User creator) { this.creator = creator; }

    public User getAssignee() { return assignee; }
    public void setAssignee(User assignee) { this.assignee = assignee; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public java.util.List<Attachment> getAttachments() { return attachments; }
}