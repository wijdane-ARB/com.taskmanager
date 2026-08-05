// PATH: src/main/java/com/taskmanager/core/repository/ChatMessageRepository.java
package com.taskmanager.core.repository;

import com.taskmanager.core.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findByTeamIdOrderByTimestampAsc(UUID teamId);
}