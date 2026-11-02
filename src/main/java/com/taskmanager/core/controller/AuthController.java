// FILE: AuthController.java
package com.taskmanager.core.controller;

import com.taskmanager.core.dto.request.*;
import com.taskmanager.core.dto.response.ApiResponse;
import com.taskmanager.core.dto.response.AuthResponse;
import com.taskmanager.core.dto.response.RegisterResponse;
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
 * Public (no Bearer token): register, login, refresh, verify-email,
 * resend-verification, forgot-password, reset-password — see SecurityConfig.
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
     *
     * Creates the account unverified and emails a 6-digit code. No token
     * pair is returned — Flutter should route to the verify-email screen,
     * passing along the email from the response.
     *
     * 201 Created / 400 Bad Request / 409 Conflict (email already registered).
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        RegisterResponse response = authService.register(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Account created. Check your email for a verification code.", response));
    }

    /**
     * POST /api/v1/auth/login
     * 200 OK / 401 on bad credentials or an unverified account.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        AuthResponse response = authService.login(request, extractDeviceInfo(httpRequest));
        return ResponseEntity.ok(ApiResponse.ok("Login successful", response));
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthResponse>> googleLogin(
            @Valid @RequestBody GoogleLoginRequest request,
            HttpServletRequest httpRequest
    ) {
        AuthResponse response = authService.loginWithGoogle(request, extractDeviceInfo(httpRequest));
        return ResponseEntity.ok(ApiResponse.ok("Google login successful", response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshRequest request,
            HttpServletRequest httpRequest
    ) {
        AuthResponse response = authService.refresh(request, extractDeviceInfo(httpRequest));
        return ResponseEntity.ok(ApiResponse.ok("Token refreshed", response));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/v1/auth/verify-email
     * Confirms the registration code and logs the user in immediately.
     * 200 OK / 400 Bad Request if the code is wrong, expired, or already used.
     */
    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request,
            HttpServletRequest httpRequest
    ) {
        AuthResponse response = authService.verifyEmail(request, extractDeviceInfo(httpRequest));
        return ResponseEntity.ok(ApiResponse.ok("Email verified", response));
    }

    /**
     * POST /api/v1/auth/resend-verification
     * Always 200 — never reveals whether the email exists or is already verified.
     *
     * FIX: was bound to UpdateUserRequest.ResendVerificationRequest, a
     * duplicate nested record accidentally left in UpdateUserRequest.java.
     * That type doesn't match what AuthService.resendVerificationCode()
     * declares (the standalone ResendVerificationRequest record), so this
     * failed to compile. Now bound to the correct top-level DTO, and the
     * duplicate nested record has been removed from UpdateUserRequest.java.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse<Void>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request
    ) {
        authService.resendVerificationCode(request);
        return ResponseEntity.ok(ApiResponse.ok(
                "If that account exists and isn't verified yet, a new code has been sent.", null));
    }

    /**
     * POST /api/v1/auth/forgot-password
     * Always 200 — never reveals whether the email exists.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request
    ) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(ApiResponse.ok(
                "If that email is registered, a reset code has been sent.", null));
    }

    /**
     * POST /api/v1/auth/reset-password
     * On success, every existing session for the account is revoked.
     * 200 OK / 400 Bad Request if the code is wrong, expired, or already used.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.ok("Password reset. Please log in again.", null));
    }

    private String extractDeviceInfo(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) return "Unknown";
        return userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent;
    }
}