// FILE: RegisterResponse.java
package com.taskmanager.core.dto.response;

import java.util.UUID;

/**
 * Returned by POST /api/v1/auth/register now that registration no longer
 * logs the user in immediately (login requires emailVerified = true).
 * Flutter uses `email` to pre-fill the verify-email screen.
 */
public record RegisterResponse(

        UUID userId,
        String email,
        boolean emailVerified

) {}