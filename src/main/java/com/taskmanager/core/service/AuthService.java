// FILE: AuthService.java
package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.*;
import com.taskmanager.core.dto.response.AuthResponse;
import com.taskmanager.core.dto.response.RegisterResponse;

import java.util.UUID;

public interface AuthService {

    /**
     * Creates a new (unverified) account and sends a verification code by
     * email. No token pair is issued — login is blocked until verifyEmail()
     * succeeds.
     */
    RegisterResponse register(RegisterRequest request);

    /**
     * Validates credentials and returns a token pair.
     * Throws UnauthorizedAccessException on bad credentials (never reveals
     * whether the email exists) and also if the account exists but the
     * email has not been verified yet.
     */
    AuthResponse login(LoginRequest request, String deviceInfo);

    AuthResponse refresh(RefreshRequest request, String deviceInfo);

    void logout(UUID userId);

    /**
     * Confirms the 6-digit code sent on registration, marks the account
     * verified, and logs the user in immediately with a fresh token pair.
     */
    AuthResponse verifyEmail(VerifyEmailRequest request, String deviceInfo);

    /**
     * Issues a new verification code, invalidating any previous one.
     * Silently no-ops if the account doesn't exist or is already verified —
     * never leaks account state to the caller.
     */
    void resendVerificationCode(ResendVerificationRequest request);

    /**
     * Issues a password-reset code if the account exists. Always returns
     * the same generic outcome regardless, to avoid account enumeration.
     */
    void forgotPassword(ForgotPasswordRequest request);

    /**
     * Validates the reset code, updates the password, and revokes all
     * existing refresh tokens (forces re-login everywhere).
     */
    void resetPassword(ResetPasswordRequest request);
}