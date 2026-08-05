package com.taskmanager.core.entity;

import com.taskmanager.core.enums.ProjectStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A Project is the primary grouping unit for Tasks and now belongs to a
 * Team rather than a single User. Every member of the team has access.
 *
 * 'owner_id' column is kept (mapped to createdBy) for audit purposes only —
 * it no longer gates access. Team membership does that now.
 *
 * The free tier is limited to 5 active projects PER TEAM — enforced in
 * the service layer against the team owner's subscription status.
 */
@Entity
@Table(
        name = "projects",
        indexes = {
                @Index(name = "idx_projects_team_id", columnList = "team_id"),
                @Index(name = "idx_projects_status", columnList = "status")
        }
)
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank
    @Size(min = 1, max = 150)
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Size(max = 500)
    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "color", length = 7)
    private String color = "#6366F1";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProjectStatus status = ProjectStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Team is the access boundary — any TeamMember can see/use this project.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    // Audit only ("created by") — never used for access checks.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User createdBy;

    @OneToMany(
            mappedBy = "project",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<Task> tasks = new ArrayList<>();

    protected Project() {}

    public Project(String name, String description, Team team, User createdBy) {
        this.name = name;
        this.description = description;
        this.team = team;
        this.createdBy = createdBy;
    }

    public void addTask(Task task) {
        tasks.add(task);
        task.setProject(this);
    }

    public void removeTask(Task task) {
        tasks.remove(task);
        task.setProject(null);
    }

    public UUID getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus status) { this.status = status; }

    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }

    public User getCreatedBy() { return createdBy; }

    public List<Task> getTasks() { return tasks; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}