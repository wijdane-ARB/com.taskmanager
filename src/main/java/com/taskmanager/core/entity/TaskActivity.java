package com.taskmanager.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_activities")
public class TaskActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "description")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TaskActivity() {}

    public TaskActivity(Task task, User user, String actionType, String description) {
        this.task = task;
        this.user = user;
        this.actionType = actionType;
        this.description = description;
    }

    public UUID getId() { return id; }
    public Task getTask() { return task; }
    public User getUser() { return user; }
    public String getActionType() { return actionType; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}