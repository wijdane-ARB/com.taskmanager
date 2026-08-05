package com.taskmanager.core.entity;

import com.taskmanager.core.enums.InviteStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "team_invites", indexes = @Index(name = "idx_team_invites_email_status", columnList = "invited_email, status"))
public class TeamInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "invited_email", nullable = false, length = 255)
    private String invitedEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by", nullable = false)
    private User invitedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InviteStatus status = InviteStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected TeamInvite() {}

    public TeamInvite(Team team, String invitedEmail, User invitedBy) {
        this.team = team;
        this.invitedEmail = invitedEmail;
        this.invitedBy = invitedBy;
    }

    public void accept() { this.status = InviteStatus.ACCEPTED; this.respondedAt = Instant.now(); }
    public void decline() { this.status = InviteStatus.DECLINED; this.respondedAt = Instant.now(); }

    public UUID getId() { return id; }
    public Team getTeam() { return team; }
    public String getInvitedEmail() { return invitedEmail; }
    public User getInvitedBy() { return invitedBy; }
    public InviteStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRespondedAt() { return respondedAt; }
}