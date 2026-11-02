package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/auth/register
 *
 * Password rules change from original:
 *
 * Previously only @Size(min=8, max=72) was enforced — any 8-character string
 * including "aaaaaaaa" was accepted. This is length-only validation and provides
 * very little real security improvement over shorter passwords if users choose
 * keyboard walks or dictionary words.
 *
 * The @Pattern below enforces NIST SP 800-63B composition rules:
 *   - At least one uppercase letter   [A-Z]
 *   - At least one lowercase letter   [a-z]
 *   - At least one digit              [0-9]
 *   - At least one special character  [^A-Za-z0-9] (any non-alphanumeric)
 *
 * Length constraints are kept via @Size — the two annotations work together.
 * @Size runs first (cheaper); @Pattern only runs if length passes.
 *
 * The max is 72 because BCrypt silently truncates input at 72 bytes —
 * the @Size max makes this contract explicit to the caller.
 *
 * UX note: the validation failure message is intentionally descriptive so
 * Flutter can display it inline rather than a generic "password too weak".
 */
public record RegisterRequest(

        @NotBlank(message = "Display name is required")
        @Size(min = 1, max = 100, message = "Display name must be between 1 and 100 characters")
        String displayName,

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        String email,

        @NotBlank(message = "Password is required")
        @Size(
                min = 8,
                message = "Password must be at least 8 characters"
        )
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
                message = "Password must contain at least one uppercase letter, " +
                        "one lowercase letter, one digit, and one special character"
        )
        String password

) {}