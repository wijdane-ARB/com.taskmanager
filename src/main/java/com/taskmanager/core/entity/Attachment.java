package com.taskmanager.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attachments", indexes = @Index(name = "idx_attachments_task_id", columnList = "task_id"))
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected Attachment() {}

    public Attachment(Task task, String fileName, String fileUrl) {
        this.task = task;
        this.fileName = fileName;
        this.fileUrl = fileUrl;
    }

    public UUID getId() { return id; }
    public Task getTask() { return task; }
    public String getFileName() { return fileName; }
    public String getFileUrl() { return fileUrl; }
    public Instant getUploadedAt() { return uploadedAt; }
}