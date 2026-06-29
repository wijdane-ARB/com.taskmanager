package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.LoginRequest;
import com.taskmanager.core.dto.request.RefreshRequest;
import com.taskmanager.core.dto.request.RegisterRequest;
import com.taskmanager.core.dto.response.AuthResponse;

public interface AuthService {

    /**
     * Creates a new user account and returns a ready-to-use token pair.
     * Flutter logs the user in immediately after registration — no separate
     * login step required.
     */
    AuthResponse register(RegisterRequest request, String deviceInfo);

    /**
     * Validates credentials and returns a token pair.
     * Throws UnauthorizedAccessException on bad credentials (never reveals
     * whether the email exists — always the same generic error message).
     */
    AuthResponse login(LoginRequest request, String deviceInfo);

    /**
     * Exchanges a valid, non-revoked refresh token for a new token pair.
     * The old refresh token is revoked immediately (rotation).
     * Throws UnauthorizedAccessException if the token is invalid, expired, or revoked.
     */
    AuthResponse refresh(RefreshRequest request, String deviceInfo);

    /**
     * Revokes all refresh tokens for the calling user.
     * The access token will continue to work until it expires naturally —
     * this is acceptable for the 15-minute TTL. For instant revocation,
     * add a Redis blocklist keyed on jti (JWT ID).
     */
    void logout(java.util.UUID userId);
}
