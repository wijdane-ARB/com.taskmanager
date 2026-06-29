package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.UpdateUserRequest;
import com.taskmanager.core.dto.response.UserResponse;

import java.util.UUID;

public interface UserService {

    /**
     * Returns the authenticated user's own profile.
     * Called by Flutter on every app resume after a token refresh,
     * to keep the profile screen in sync without a separate login.
     *
     * Throws ResourceNotFoundException if the user ID from the JWT
     * no longer exists in the DB (deleted account edge case).
     */
    UserResponse getMe(UUID userId);

    /**
     * Partial update of the authenticated user's own profile.
     * Currently supports displayName only — email changes require
     * a verification flow and are deferred to a later release.
     *
     * Throws ResourceNotFoundException if the user no longer exists.
     */
    UserResponse updateMe(UUID userId, UpdateUserRequest request);
}
