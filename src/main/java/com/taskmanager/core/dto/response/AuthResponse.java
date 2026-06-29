package com.taskmanager.core.dto.response;

import java.util.UUID;

/**
 * Returned by register, login, and refresh endpoints.
 *
 * Flutter storage strategy:
 *   - accessToken  → in-memory only (never persisted; gone when app process dies)
 *   - refreshToken → flutter_secure_storage (survives app restarts)
 *
 * The accessTokenExpiresInSeconds field lets the Flutter HTTP interceptor
 * schedule a proactive token refresh slightly before expiry, avoiding
 * the user ever seeing a 401.
 *
 * User fields are included so Flutter can pre-populate the profile screen
 * immediately after login without making a separate GET /users/me call.
 */
public record AuthResponse(

        String accessToken,
        String refreshToken,
        long accessTokenExpiresInSeconds,

        // Embedded user info — avoids a round-trip after login
        UUID userId,
        String displayName,
        String email,
        boolean isProSubscriber

) {}
