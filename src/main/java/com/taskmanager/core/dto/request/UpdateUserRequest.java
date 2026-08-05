package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.Size;

/**
 * PATCH /api/v1/users/me
 *
 * All fields are nullable — service only applies non-null values.
 * Email is intentionally excluded: changing email requires a
 * verification flow (send link → confirm → update) which is out of
 * scope for the initial release. Password changes use the dedicated
 * PUT /api/v1/users/me/password endpoint instead.
 *
 * FIX: removed the nested ResendVerificationRequest record that used to
 * live here. It was an accidental duplicate of the standalone
 * ResendVerificationRequest.java — same fields, different Java type —
 * and AuthController was mistakenly binding /auth/resend-verification's
 * request body to this nested copy instead of the type AuthService
 * actually declares (ResendVerificationRequest), which failed to compile.
 * The standalone record is now the single source of truth.
 */
public record UpdateUserRequest(

        @Size(min = 1, max = 100, message = "Display name must be between 1 and 100 characters")
        String displayName

) {}