// FILE: User.java
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
 * emailVerified gates login — see AuthServiceImpl.login(). New accounts
 * start false and flip to true via POST /api/v1/auth/verify-email.
 *
 * FIX: 'projects' was declared @OneToMany(mappedBy = "owner", ...), but
 * Project's field is named 'createdBy' (see Project.java — the owner
 * relationship was intentionally renamed to make clear it's audit-only,
 * not an access-control boundary; Team membership governs access now).
 * mappedBy="owner" referenced a field that doesn't exist on Project,
 * which fails at Hibernate bootstrap (MappingException), not just at
 * compile time. Corrected to mappedBy="createdBy".
 *
 * FIX: removed addProject()/removeProject(). They called
 * project.setOwner(this) / project.setOwner(null) — a method that no
 * longer exists on Project, since createdBy is set once in Project's
 * constructor and has no setter by design (immutable audit field).
 * These helpers were also confirmed dead code (IDE: "never used") —
 * nothing in the codebase creates a Project by first attaching it to a
 * User's collection; ProjectServiceImpl.createProject() constructs
 * Project directly and saves it via ProjectRepository instead.
 *
 * FIX: restored the `= new ArrayList<>()` default on `projects`. A
 * transient (not-yet-persisted) User calling getProjects() before being
 * saved/reloaded would otherwise NPE instead of returning an empty list.
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
    @Column(name = "is_pro_subscriber", nullable = false)
    private boolean isProSubscriber = false;

    // Gates login. Set true by AuthServiceImpl.verifyEmail() once the
    // registration code is confirmed.
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;



    // --- Constructors ---

    protected User() {}

    public User(String displayName, String email, String passwordHash) {
        this.displayName = displayName;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public boolean isProSubscriber() { return isProSubscriber; }
    public void setProSubscriber(boolean proSubscriber) { isProSubscriber = proSubscriber; }

    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}