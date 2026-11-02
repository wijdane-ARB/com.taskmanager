// PATH: src/main/java/com/taskmanager/core/controller/ChatController.java
package com.taskmanager.core.controller;

import com.taskmanager.core.dto.request.ChatMessageRequest;
import com.taskmanager.core.dto.request.ChatTypingRequest;
import com.taskmanager.core.dto.response.ApiResponse;
import com.taskmanager.core.dto.response.ChatMessageResponse;
import com.taskmanager.core.entity.ChatMessage;
import com.taskmanager.core.entity.Team;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.exception.ForbiddenException;
import com.taskmanager.core.exception.UnauthorizedAccessException;
import com.taskmanager.core.repository.ChatMessageRepository;
import com.taskmanager.core.repository.TeamRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.service.TeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import java.time.Duration;

/**
 * Chat is scoped to the TEAM, not to any individual project — see
 * ChatMessage's Javadoc. All members of a team share one stream.
 * SECURITY FIX: previously neither the STOMP send handler nor the REST
 * history endpoint verified team membership before granting access to a
 * team's chat stream. Any authenticated user could read or post to any
 * team's chat by guessing/enumerating UUIDs. Both paths now go through
 * TeamService.hasTeamAccess(). The matching fix on the SUBSCRIBE side
 * lives in JwtChannelInterceptor — that check is what actually stops an
 * unauthorized client from receiving the broadcast in the first place;
 * the checks here stop unauthorized reads/writes at the source.
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TeamService teamService;
    private final LettuceBasedProxyManager<byte[]> proxyManager;

    public ChatController(SimpMessagingTemplate messagingTemplate,
                          UserRepository userRepository,
                          TeamRepository teamRepository,
                          ChatMessageRepository chatMessageRepository,
                          TeamService teamService,
                          LettuceBasedProxyManager<byte[]> proxyManager) {
        this.messagingTemplate = messagingTemplate;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.teamService = teamService;
        this.proxyManager = proxyManager;
    }

    private Bucket getBucket(String key, int limit, Duration window) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(limit, Refill.intervally(limit, window)))
                .build();
        return proxyManager.builder().build(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), configuration);
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload @Valid ChatMessageRequest request, Principal principal) {
        if (principal == null) {
            throw new UnauthorizedAccessException("Unauthenticated STOMP request");
        }

        UUID senderId = UUID.fromString(principal.getName());

        Bucket sendBucket = getBucket("chat-send::" + senderId, 20, Duration.ofSeconds(10));
        if (!sendBucket.tryConsume(1)) {
            throw new RuntimeException("Too many messages sent. Please slow down.");
        }

        if (!teamService.hasTeamAccess(request.teamId(), senderId)) {
            log.warn("User {} attempted to send a chat message to team {} without membership",
                    senderId, request.teamId());
            throw new ForbiddenException("You are not a member of this team.");
        }

        if (request.clientId() != null) {
            java.util.Optional<ChatMessage> existing = chatMessageRepository.findByTeamIdAndSenderIdAndClientId(
                    request.teamId(), senderId, request.clientId());
            if (existing.isPresent()) {
                ChatMessage msg = existing.get();
                ChatMessageResponse response = new ChatMessageResponse(
                        msg.getId(), msg.getTeam().getId(), msg.getSender().getId(),
                        msg.getSender().getDisplayName(), msg.getContent(), msg.getTimestamp(), msg.getClientId()
                );
                messagingTemplate.convertAndSend("/topic/teams/" + request.teamId() + "/chat", response);
                return;
            }
        }

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + senderId));
        Team team = teamRepository.getReferenceById(request.teamId());

        ChatMessage savedMessage = chatMessageRepository.save(
                new ChatMessage(team, sender, request.content().trim(), request.clientId())
        );

        ChatMessageResponse response = new ChatMessageResponse(
                savedMessage.getId(),
                team.getId(),
                sender.getId(),
                sender.getDisplayName(),
                savedMessage.getContent(),
                savedMessage.getTimestamp(),
                savedMessage.getClientId()
        );

        messagingTemplate.convertAndSend(
                "/topic/teams/" + team.getId() + "/chat",
                response
        );
    }

    @MessageMapping("/chat.typing")
    public void handleTyping(@Payload @Valid ChatTypingRequest request, Principal principal) {
        if (principal == null) return;

        UUID userId = UUID.fromString(principal.getName());

        Bucket typingBucket = getBucket("chat-typing::" + userId, 5, Duration.ofSeconds(1));
        if (!typingBucket.tryConsume(1)) {
            return; // silently drop
        }

        // Single optimized query verifying membership and returning display name directly
        userRepository.findDisplayNameIfTeamMember(userId, request.teamId()).ifPresent(displayName -> {
            messagingTemplate.convertAndSend(
                    "/topic/teams/" + request.teamId() + "/typing",
                    (Object) Map.of(
                            "teamId", request.teamId(),
                            "userId", userId,
                            "userName", displayName,
                            "isTyping", request.isTyping()
                    )
            );
        });
    }

    @MessageExceptionHandler
    public void handleMessageException(Throwable exception, Principal principal) {
        if (principal != null) {
            log.warn("STOMP message error for user {}: {}", principal.getName(), exception.getMessage());
            
            String errorMessage = "Failed to process chat message";
            if (exception instanceof ForbiddenException) {
                errorMessage = "You are not a member of this team.";
            } else if (exception instanceof org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException) {
                errorMessage = "Invalid message format.";
            } else if (exception instanceof RuntimeException && exception.getMessage().contains("slow down")) {
                errorMessage = exception.getMessage();
            }
            
            messagingTemplate.convertAndSendToUser(
                    principal.getName(),
                    "/queue/errors",
                    Map.of(
                        "success", false,
                        "message", errorMessage
                    )
            );
        }
    }

    // Handles HTTP requests to load history
    @GetMapping("/api/v1/teams/{teamId}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getMessageHistory(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID teamId,
            @RequestParam(required = false) java.time.Instant before,
            @RequestParam(defaultValue = "50") int size) {

        if (!teamService.hasTeamAccess(teamId, currentUserId)) {
            throw new ForbiddenException("You are not a member of this team.");
        }

        // Clamp size between 1 and 100 to prevent size=0 (500 error) or size=1000000 (OOM)
        int clampedSize = Math.max(1, Math.min(size, 100));
        Pageable limit = PageRequest.of(0, clampedSize);

        List<ChatMessage> messages = (before != null)
                ? chatMessageRepository.findByTeamIdAndTimestampLessThanOrderByTimestampDesc(teamId, before, limit)
                : chatMessageRepository.findByTeamIdOrderByTimestampDesc(teamId, limit);

        List<ChatMessageResponse> history = messages.stream()
                .map(msg -> new ChatMessageResponse(
                        msg.getId(),
                        msg.getTeam().getId(),
                        msg.getSender().getId(),
                        msg.getSender().getDisplayName(),
                        msg.getContent(),
                        msg.getTimestamp(),
                        msg.getClientId()
                ))
                .collect(Collectors.toList());

        // Reverse so the client receives them chronologically (oldest at index 0, newest at end)
        Collections.reverse(history);

        return ResponseEntity.ok(ApiResponse.ok(history));
    }
}