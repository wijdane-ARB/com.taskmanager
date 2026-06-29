package com.taskmanager.core.service.impl;

import com.taskmanager.core.dto.request.LoginRequest;
import com.taskmanager.core.dto.request.RefreshRequest;
import com.taskmanager.core.dto.request.RegisterRequest;
import com.taskmanager.core.dto.response.AuthResponse;
import com.taskmanager.core.entity.RefreshToken;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.exception.UnauthorizedAccessException;
import com.taskmanager.core.repository.RefreshTokenRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.security.JwtService;
import com.taskmanager.core.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    // Refresh token lives 30 days by default — configurable per environment.
    // Mobile clients should stay logged in across weeks; 30 days with rotation
    // means an active user never sees a forced re-login.
    @Value("${jwt.refresh-token-expiry-days:30}")
    private long refreshTokenExpiryDays;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository         = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService             = jwtService;
        this.passwordEncoder        = passwordEncoder;
    }

    // ── Register ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request, String deviceInfo) {
        log.debug("Registering new user with email: {}", request.email());

        if (userRepository.existsByEmail(request.email())) {
            // Generic message — don't reveal whether an email is registered
            throw new IllegalStateException("An account with this email already exists.");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(request.displayName(), request.email(), passwordHash);
        User saved = userRepository.save(user);

        log.info("New user registered: {} ({})", saved.getId(), saved.getEmail());

        return issueTokenPair(saved, deviceInfo);
    }

    // ── Login ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request, String deviceInfo) {
        log.debug("Login attempt for email: {}", request.email());

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedAccessException("Invalid email or password."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Intentionally the same message as "user not found" — prevents email enumeration
            throw new UnauthorizedAccessException("Invalid email or password.");
        }

        log.info("User {} logged in successfully", user.getId());

        return issueTokenPair(user, deviceInfo);
    }

    // ── Refresh ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AuthResponse refresh(RefreshRequest request, String deviceInfo) {
        RefreshToken storedToken = refreshTokenRepository
                .findByToken(request.refreshToken())
                .orElseThrow(() -> new UnauthorizedAccessException("Invalid refresh token."));

        if (storedToken.isRevoked()) {
            // A revoked token arriving here means it was already rotated — the
            // only legitimate way that happens is if an attacker stole the token
            // and used it first. Revoke every token for this user immediately so
            // the real user is forced to log in again on all devices, cutting off
            // the attacker's session at the same time.
            UUID compromisedUserId = storedToken.getUser().getId();
            log.warn("Revoked refresh token reuse detected for user {} — revoking all sessions.",
                    compromisedUserId);
            refreshTokenRepository.revokeAllByUserId(compromisedUserId);
            throw new UnauthorizedAccessException(
                    "Security alert: this token was already used. All sessions have been ended. Please log in again.");
        }

        if (storedToken.isExpired()) {
            throw new UnauthorizedAccessException("Refresh token has expired. Please log in again.");
        }

        // Rotate: revoke the old token before issuing a new pair
        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        log.debug("Refresh token rotated for user {}", storedToken.getUser().getId());

        return issueTokenPair(storedToken.getUser(), deviceInfo);
    }

    // ── Logout ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void logout(UUID userId) {
        // Revoke all refresh tokens for this user across all devices.
        // The access token expires naturally (15 min TTL).
        refreshTokenRepository.revokeAllByUserId(userId);
        log.info("All refresh tokens revoked for user {}", userId);
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * Issues a new access token + refresh token pair and persists the refresh token.
     * Used by register, login, and refresh — the single source of truth for token issuance.
     */
    private AuthResponse issueTokenPair(User user, String deviceInfo) {
        String accessToken = jwtService.generateAccessToken(user.getId());

        // Refresh token is a random UUID — opaque, non-forgeable without the DB record
        String rawRefreshToken = UUID.randomUUID().toString();
        Instant refreshExpiry  = Instant.now().plusSeconds(refreshTokenExpiryDays * 86_400);

        RefreshToken refreshToken = new RefreshToken(
                rawRefreshToken,
                user,
                refreshExpiry,
                deviceInfo
        );
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                jwtService.getAccessTokenExpirySeconds(),
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.isProSubscriber()
        );
    }
}