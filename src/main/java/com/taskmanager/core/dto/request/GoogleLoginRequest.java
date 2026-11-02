package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
    @NotBlank(message = "ID Token is required")
    String idToken
) {}
