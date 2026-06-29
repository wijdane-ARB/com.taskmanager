package com.taskmanager.core.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Core User entity. Uses UUID as the primary key to avoid
 * enumerable integer IDs being exposed in public APIs.
 *
 * The 'projects' relationship uses CascadeType.ALL — deleting a
 * user cascades to their projects, which cascades further to tasks.
 * This enforces referential integrity at the JPA layer, not just the DB.
 */
@Entity
@Table(
        name = "users",
        indexes = {
                @Index(name = "idx_users_email", columnList = "email", unique = true)
        }
)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank
    @Size(min = 1, max = 100)
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Email
    @NotBlank
    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    // Password hash — stored as BCrypt output (60 chars fixed).
    // Never returned in any API response.
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    // Tracks whether the user has an active Pro subscription.
    // Checked by the API gateway / service layer before serving
    // any Pro-tier features (AI scheduling, deep reports, etc.)
    @Column(name = "is_pro_subscriber", nullable = false)
    private boolean isProSubscriber = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // One user owns many projects. FetchType.LAZY is mandatory —
    // never eagerly load a collection on a root entity.
    @OneToMany(
            mappedBy = "owner",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<Project> projects = new ArrayList<>();

    // --- Constructors ---

    protected User() {}

    public User(String displayName, String email, String passwordHash) {
        this.displayName = displayName;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    // --- Convenience relationship helpers ---

    public void addProject(Project project) {
        projects.add(project);
        project.setOwner(this);
    }

    public void removeProject(Project project) {
        projects.remove(project);
        project.setOwner(null);
    }

    // --- Getters & Setters ---

    public UUID getId() { return id; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public boolean isProSubscriber() { return isProSubscriber; }
    public void setProSubscriber(boolean proSubscriber) { isProSubscriber = proSubscriber; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public List<Project> getProjects() { return projects; }
}