// PATH: src/main/java/com/taskmanager/core/security/JwtChannelInterceptor.java
package com.taskmanager.core.security;

import com.taskmanager.core.service.TeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SECURITY: authenticates STOMP sessions on CONNECT and authorizes every
 * SUBSCRIBE against team membership. Spring's simple broker has no
 * per-destination ACL of its own, so without this, any authenticated user
 * could subscribe to /topic/teams/{any-uuid} and silently receive another
 * team's live chat.
 * Design notes (fixes applied after review):
 * 1. Rejections are handled by DROPPING the frame (returning null), not by
 *    throwing. A ChannelInterceptor exception on the client-inbound channel
 *    becomes a STOMP ERROR frame, and per the STOMP spec an ERROR frame
 *    TERMINATES THE WHOLE SESSION — including any other, legitimate
 *    subscriptions the client already holds. A single bad/late/racy
 *    subscribe (e.g. the UI switching teams) must not disconnect the
 *    client entirely.
  * 2. No denial reason is ever sent back to the client. Distinguishing
 *    "unauthenticated" vs "not a member of this team" vs "team doesn't
 *    exist" vs "malformed destination" in a client-visible message lets a
 *    client enumerate team membership and existence — the same reason
 *    TeamServiceImpl.validateInviteBelongsToCaller() intentionally returns
 *    an identical message for "not your invite" and "invite doesn't
 *    exist". All of that detail is logged server-side instead, at WARN.
  * 3. Destination parsing never throws uncaught. The topic regex only
 *    constrains character set and length, not UUID grouping, so
 *    UUID.fromString() can still throw on a regex-matching but malformed
 *    destination; that's now caught and treated as a denial like any
 *    other, not an unhandled exception.
 * 4. Unrecognized /topic or /queue destinations are default-denied rather
 *    than default-allowed, so a newly added topic can't accidentally ship
 *    unguarded. NOTE: if per-user destinations (Spring's /user/queue/**
 *    convention, e.g. via @SendToUser) are introduced later, add an
 *    explicit allow branch for them here — they will otherwise be denied
 *    by this same default-deny rule.
 */
@Component
public class JwtChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtChannelInterceptor.class);

    private static final Pattern TEAM_TOPIC_PATTERN =
            Pattern.compile("^/topic/teams/([0-9a-fA-F-]{36})$");

    private final JwtService jwtService;
    private final TeamService teamService;

    public JwtChannelInterceptor(JwtService jwtService, TeamService teamService) {
        this.jwtService = jwtService;
        this.teamService = teamService;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateConnect(accessor);
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return isSubscribeAuthorized(accessor) ? message : null;
        }

        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return; // leave accessor.getUser() null — handled as denial at SUBSCRIBE time
        }

        String token = authHeader.substring(7);

        if (!jwtService.isTokenValid(token)) {
            log.warn("STOMP CONNECT with invalid/expired token");
            return;
        }

        UUID userId = jwtService.extractUserId(token);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        accessor.setUser(auth);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Returns true iff the subscribe should be let through. Every denial
     * path logs the specific reason server-side and returns false — never
     * throws, never leaks the reason back to the client.
     */
    private boolean isSubscribeAuthorized(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            log.warn("SUBSCRIBE with no destination — denying");
            return false;
        }

        Matcher matcher = TEAM_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            // Default-deny: anything not explicitly recognized is rejected,
            // so a new topic family can't accidentally ship unguarded.
            log.warn("SUBSCRIBE to unrecognized/unguarded destination '{}' — denying", destination);
            return false;
        }

        Principal principal = accessor.getUser();
        if (principal == null) {
            log.warn("Unauthenticated SUBSCRIBE attempt to '{}' — denying", destination);
            return false;
        }

        UUID teamId;
        UUID userId;
        try {
            teamId = UUID.fromString(matcher.group(1));
            userId = UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            // Regex only constrains character set/length, not UUID grouping —
            // a malformed-but-matching destination or principal name lands here.
            log.warn("SUBSCRIBE to '{}' had an unparseable team or user id — denying", destination);
            return false;
        }

        if (!teamService.hasTeamAccess(teamId, userId)) {
            log.warn("User {} attempted to subscribe to team {} without membership — denying", userId, teamId);
            return false;
        }

        return true;
    }
}