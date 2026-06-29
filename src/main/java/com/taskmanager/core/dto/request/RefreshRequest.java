package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/v1/auth/refresh
 *
 * Flutter sends this when the access token has expired (typically detected
 * via a 401 response). The interceptor in the Flutter HTTP client should
 * automatically call this endpoint and retry the original request.
 */
public record RefreshRequest(

        @NotBlank(message = "Refresh token is required")
        String refreshToken

) {}
