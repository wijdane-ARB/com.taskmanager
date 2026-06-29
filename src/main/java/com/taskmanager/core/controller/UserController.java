package com.taskmanager.core.controller;

import com.taskmanager.core.dto.request.UpdateUserRequest;
import com.taskmanager.core.dto.response.ApiResponse;
import com.taskmanager.core.dto.response.UserResponse;
import com.taskmanager.core.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Profile endpoints for the authenticated user's own account.
 * All endpoints under /api/v1/users/me — "me" always resolves
 * to the caller from @AuthenticationPrincipal, never from a path param.
 * This makes it impossible to read or modify another user's profile.
 */
@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * GET /api/v1/users/me
     * Returns the authenticated user's own profile.
     *
     * Flutter calls this:
     *   1. On first app launch after registration (to populate the profile screen).
     *   2. After a token refresh, to confirm the account still exists and
     *      sync any server-side changes (e.g. Pro subscription status).
     *   3. When the user navigates to the profile/settings screen.
     *
     * 200 OK with UserResponse on success.
     * 404 if the JWT references a user that no longer exists (deleted account).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<UserResponse>> getMe(
            @AuthenticationPrincipal UUID currentUserId
    ) {
        UserResponse user = userService.getMe(currentUserId);
        return ResponseEntity.ok(ApiResponse.ok(user));
    }

    /**
     * PATCH /api/v1/users/me
     * Partial update — currently supports displayName only.
     *
     * Email changes require a verification flow (out of scope for v1).
     * Password changes go to PUT /api/v1/users/me/password (to be added).
     *
     * 200 OK with updated UserResponse on success.
     * 400 Bad Request on validation failure.
     */
    @PatchMapping
    public ResponseEntity<ApiResponse<UserResponse>> updateMe(
            @AuthenticationPrincipal UUID currentUserId,
            @Valid @RequestBody UpdateUserRequest request
    ) {
        UserResponse user = userService.updateMe(currentUserId, request);
        return ResponseEntity.ok(ApiResponse.ok("Profile updated", user));
    }
}
