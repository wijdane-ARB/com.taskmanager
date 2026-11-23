package com.taskmanager.core.security;

import com.taskmanager.core.event.TeamMemberRemovedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class WebSocketSessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRegistry.class);
    private static final CloseStatus MEMBERSHIP_CHANGED = CloseStatus.POLICY_VIOLATION
            .withReason("Team membership changed");

    private final Map<String, Connection> connections = new HashMap<>();

    public synchronized void register(WebSocketSession session) {
        connections.put(session.getId(), new Connection(session));
    }

    public synchronized void unregister(String sessionId) {
        connections.remove(sessionId);
    }

    public synchronized boolean bindUser(String sessionId, UUID userId) {
        Connection connection = connections.get(sessionId);
        if (connection == null || connection.revoked || connection.userId != null) {
            return false;
        }
        connection.userId = userId;
        return true;
    }

    public synchronized boolean isActive(String sessionId) {
        Connection connection = connections.get(sessionId);
        return connection != null && connection.userId != null && !connection.revoked;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void revokeSessions(TeamMemberRemovedEvent event) {
        for (WebSocketSession session : markRevoked(event.memberUserId())) {
            try {
                session.close(MEMBERSHIP_CHANGED);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not close revoked WebSocket session {}", session.getId(), e);
            }
        }
    }

    private synchronized List<WebSocketSession> markRevoked(UUID userId) {
        List<WebSocketSession> sessions = new ArrayList<>();
        for (Connection connection : connections.values()) {
            if (!connection.revoked && userId.equals(connection.userId)) {
                connection.revoked = true;
                sessions.add(connection.session);
            }
        }
        return sessions;
    }

    private static class Connection {
        private final WebSocketSession session;
        private UUID userId;
        private boolean revoked;

        private Connection(WebSocketSession session) {
            this.session = session;
        }
    }
}
