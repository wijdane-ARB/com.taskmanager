// FILE: VerificationCode.java
package com.taskmanager.core.entity;

import com.taskmanager.core.enums.VerificationCodeType;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Short-lived numeric code used for two flows:
 *   EMAIL_VERIFICATION — sent on register, confirms the email address.
 *   PASSWORD_RESET     — sent on /auth/forgot-password.
 *
 * Stored as SHA-256 hashes.
 * Old codes for the same user+type are invalidated whenever a new one is
 * issued, so only the most recently sent code ever works.
 */
@Entity
@Table(
        name = "verification_codes",
        indexes = {
                @Index(name = "idx_verification_codes_user_type", columnList = "user_id, type, used"),
                @Index(name = "idx_verification_codes_expires", columnList = "expires_at")
        }
)
public class VerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private VerificationCodeType type;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used = false;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VerificationCode() {}

    public VerificationCode(User user, String code, VerificationCodeType type, Instant expiresAt) {
        this.user = user;
        this.code = code;
        this.type = type;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid(String hashedCandidate) {
        return !used && !isExpired() && code.equals(hashedCandidate);
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public String getCode() { return code; }
    public VerificationCodeType getType() { return type; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
    
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getCreatedAt() { return createdAt; }
}