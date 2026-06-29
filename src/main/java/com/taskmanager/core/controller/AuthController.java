package com.taskmanager.core.controller;

import com.taskmanager.core.dto.request.LoginRequest;
import com.taskmanager.core.dto.request.RefreshRequest;
import com.taskmanager.core.dto.request.RegisterRequest;
import com.taskmanager.core.dto.response.ApiResponse;
import com.taskmanager.core.dto.response.AuthResponse;
import com.taskmanager.core.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * All auth endpoints live under /api/v1/auth.
 * These three are declared public in SecurityConfig — no Bearer token required.
 *
 * The User-Agent header is captured as deviceInfo so the RefreshToken
 * record knows which device it was issued to. This enables a "logged-in devices"
 * screen in the Flutter app later (similar to WhatsApp / Telegram).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * POST /api/v1/auth/register
     * Creates a new account and returns a token pair immediately.
     * Flutter does not need a separate login call after registration.
     *
     * 201 Created on success.
     * 400 Bad Request on validation failure (e.g., weak password, invalid email).
     * 409 Conflict if email is already registered.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest
    ) {
        AuthResponse response = authService.register(request, extractDeviceInfo(httpRequest));
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Account created successfully", response));
    }

    /**
     * POST /api/v1/auth/login
     * Validates credentials and returns a token pair.
     *
     * 200 OK on success.
     * 401 Forbidden on bad credentials (never reveals whether email exists).
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        AuthResponse response = authService.login(request, extractDeviceInfo(httpRequest));
        return ResponseEntity.ok(ApiResponse.ok("Login successful", response));
    }

    /**
     * POST /api/v1/auth/refresh
     * Exchanges a refresh token for a new access token + new refresh token.
     * Flutter's HTTP interceptor should call this automatically on 401.
     *
     * 200 OK on success.
     * 401 if the refresh token is invalid, expired, or already revoked.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshRequest request,
            HttpServletRequest httpRequest
    ) {
        AuthResponse response = authService.refresh(request, extractDeviceInfo(httpRequest));
        return ResponseEntity.ok(ApiResponse.ok("Token refreshed", response));
    }

    /**
     * POST /api/v1/auth/logout
     * Revokes all refresh tokens for the calling user (all devices).
     * Requires a valid access token — so the user must be authenticated.
     *
     * Returns 204 No Content — nothing to return after logout.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal UUID currentUserId
    ) {
        authService.logout(currentUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Extracts device info from the User-Agent header.
     * Flutter's http package sends a User-Agent automatically.
     * Truncated to 255 chars to match the DB column constraint.
     */
    private String extractDeviceInfo(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) return "Unknown";
        return userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent;
    }
}