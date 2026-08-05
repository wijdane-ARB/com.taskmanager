// PATH: src/main/java/com/taskmanager/core/controller/ChatController.java
package com.taskmanager.core.controller;

import com.taskmanager.core.dto.request.ChatMessageRequest;
import com.taskmanager.core.dto.response.ApiResponse;
import com.taskmanager.core.dto.response.ChatMessageResponse;
import com.taskmanager.core.entity.ChatMessage;
import com.taskmanager.core.entity.Team;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.exception.UnauthorizedAccessException;
import com.taskmanager.core.repository.ChatMessageRepository;
import com.taskmanager.core.repository.TeamRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.service.TeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Chat is scoped to the TEAM, not to any individual project — see
 * ChatMessage's Javadoc. All members of a team share one stream.
 *
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

    public ChatController(SimpMessagingTemplate messagingTemplate,
                          UserRepository userRepository,
                          TeamRepository teamRepository,
                          ChatMessageRepository chatMessageRepository,
                          TeamService teamService) {
        this.messagingTemplate = messagingTemplate;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.teamService = teamService;
    }

    // Handles real-time STOMP messages
    @MessageMapping("/chat.send")
    public void sendMessage(@Payload ChatMessageRequest request, Principal principal) {
        if (principal == null) return;

        UUID senderId = UUID.fromString(principal.getName());

        // Reject silently-ish: a malicious client sending to a team it's not
        // in should not get any signal back distinguishing "wrong team" from
        // "team doesn't exist" — same principle as the REST 403/404 pattern
        // elsewhere in this codebase.
        if (!teamService.hasTeamAccess(request.teamId(), senderId)) {
            log.warn("User {} attempted to send a chat message to team {} without membership",
                    senderId, request.teamId());
            return;
        }

        User sender = userRepository.findById(senderId).orElseThrow();
        Team team = teamRepository.findById(request.teamId())
                .orElseThrow(() -> new ResourceNotFoundException("Team not found"));

        ChatMessage savedMessage = chatMessageRepository.save(
                new ChatMessage(team, sender, request.content())
        );

        ChatMessageResponse response = new ChatMessageResponse(
                savedMessage.getId(),
                team.getId(),
                sender.getId(),
                sender.getDisplayName(),
                savedMessage.getContent(),
                savedMessage.getTimestamp()
        );

        messagingTemplate.convertAndSend(
                "/topic/teams/" + team.getId(),
                response
        );
    }

    // Handles HTTP requests to load history
    @GetMapping("/api/v1/teams/{teamId}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getMessageHistory(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID teamId) {

        if (!teamService.hasTeamAccess(teamId, currentUserId)) {
            throw new UnauthorizedAccessException("You are not a member of this team.");
        }

        List<ChatMessageResponse> history = chatMessageRepository
                .findByTeamIdOrderByTimestampAsc(teamId)
                .stream()
                .map(msg -> new ChatMessageResponse(
                        msg.getId(),
                        msg.getTeam().getId(),
                        msg.getSender().getId(),
                        msg.getSender().getDisplayName(),
                        msg.getContent(),
                        msg.getTimestamp()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.ok(history));
    }
}