package com.taskmanager.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_comments")
public class TaskComment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "content", nullable = false)
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TaskComment() {}

    public TaskComment(Task task, User user, String content) {
        this.task = task;
        this.user = user;
        this.content = content;
    }

    public UUID getId() { return id; }
    public Task getTask() { return task; }
    public User getUser() { return user; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}