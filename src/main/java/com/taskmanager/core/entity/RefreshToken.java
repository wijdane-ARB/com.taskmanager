package com.taskmanager.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted refresh token. Storing it server-side (vs. a pure stateless JWT)
 * gives us the ability to revoke it immediately on logout or password change.
 *
 * Token rotation: each /auth/refresh call issues a new refresh token and
 * revokes the old one.
 *
 * FIX: `token` is now length = 64. Since V13 the column stores the SHA-256
 * hex digest of the raw token (64 chars), not the 36-char UUID.
 */
@Entity
@Table(
        name = "refresh_tokens",
        indexes = {
                @Index(name = "idx_refresh_tokens_token",   columnList = "token",   unique = true),
                @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"),
                @Index(name = "idx_refresh_tokens_expires", columnList = "expires_at")
        }
)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** SHA-256 hex digest of the raw token sent to the client. */
    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    @Column(name = "device_info", length = 255)
    private String deviceInfo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @org.hibernate.annotations.UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RefreshToken() {}

    public RefreshToken(String token, User user, Instant expiresAt, String deviceInfo) {
        this.token      = token;
        this.user       = user;
        this.expiresAt  = expiresAt;
        this.deviceInfo = deviceInfo;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(this.expiresAt);
    }

    public UUID getId()           { return id; }
    public String getToken()      { return token; }
    public User getUser()         { return user; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked()    { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
    public String getDeviceInfo() { return deviceInfo; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}