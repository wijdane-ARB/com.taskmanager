// PATH: src/main/java/com/taskmanager/core/entity/ChatMessage.java
package com.taskmanager.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A message in a team's shared chat. Chat is scoped to the Team, not to
 * any individual Project — all of a team's members see the same stream,
 * regardless of which of the team's projects they're currently working in.
 */
@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_team_id", columnList = "team_id")
})
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Column(name = "client_id")
    private UUID clientId;

    @CreationTimestamp
    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    protected ChatMessage() {}

    public ChatMessage(Team team, User sender, String content, UUID clientId) {
        this.team = team;
        this.sender = sender;
        this.content = content;
        this.clientId = clientId;
    }

    public UUID getId() { return id; }
    public Team getTeam() { return team; }
    public User getSender() { return sender; }
    public String getContent() { return content; }
    public UUID getClientId() { return clientId; }
    public Instant getTimestamp() { return timestamp; }
}