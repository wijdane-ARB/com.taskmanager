// PATH: src/main/java/com/taskmanager/core/repository/ChatMessageRepository.java
package com.taskmanager.core.repository;

import com.taskmanager.core.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    // Initial load: gets the latest messages
    @EntityGraph(attributePaths = {"sender"})
    List<ChatMessage> findByTeamIdOrderByTimestampDesc(UUID teamId, Pageable pageable);

    // Cursor load: gets messages strictly older than the provided timestamp
    @EntityGraph(attributePaths = {"sender"})
    List<ChatMessage> findByTeamIdAndTimestampLessThanOrderByTimestampDesc(
            UUID teamId,
            Instant timestamp,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"sender"})
    List<ChatMessage> findByTeamIdOrderByTimestampAsc(UUID teamId);

    @EntityGraph(attributePaths = {"sender", "team"})
    java.util.Optional<ChatMessage> findByTeamIdAndSenderIdAndClientId(UUID teamId, UUID senderId, UUID clientId);
}
