package com.taskmanager.core.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.taskmanager.core.dto.request.*;
import com.taskmanager.core.dto.response.AuthResponse;
import com.taskmanager.core.dto.response.RegisterResponse;
import com.taskmanager.core.entity.RefreshToken;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.entity.VerificationCode;
import com.taskmanager.core.enums.VerificationCodeType;
import com.taskmanager.core.exception.InvalidCodeException;
import com.taskmanager.core.exception.UnauthorizedAccessException;
import com.taskmanager.core.repository.RefreshTokenRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.repository.VerificationCodeRepository;
import com.taskmanager.core.security.JwtService;
import com.taskmanager.core.service.AuthService;
import com.taskmanager.core.service.TeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Changes in this version:
 *
 * SECURITY (account pre-hijack): register() used to reject any email that
 * already existed. An attacker could register a victim's email with their own
 * password; the victim then "verified" via resend and got an account that
 * still had the attacker's password. Now, registering over an UNVERIFIED
 * account overwrites its display name and password hash and sends a fresh
 * code, so whoever proves control of the inbox owns the password they chose
 * last. Verified accounts are still rejected.
 *
 * SECURITY (code storage): 6-digit codes were stored as plain SHA-256, i.e.
 * 10^6 possibilities that anyone with a DB dump can brute-force in
 * milliseconds. They are now HMAC-SHA256(serverSecret, userId:type:code),
 * so a DB leak alone is not enough. NOTE: codes issued before this deploy
 * stop working (15-minute lifetime) — users just request a new one.
 *
 * Refresh tokens still use plain SHA-256 via hashToken(): they are 122-bit
 * random UUIDs, so unsalted hashing is fine there.
 *
 * Google login: null/blank "name" now falls back to the email local-part
 * (a null name failed entity validation), and the catch-all around the whole
 * method no longer converts our own exceptions/DB errors into a misleading
 * "Google authentication failed" 401 — only token verification failures do.
 */
@Service
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${jwt.refresh-token-expiry-days:30}")
    private long refreshTokenExpiryDays;

    @Value("${app.mail.code-expiry-minutes:15}")
    private int codeExpiryMinutes;

    @Value("${app.google.client-id}")
    private String googleClientId;

    @Value("${app.security.code-hmac-secret}")
    private String codeHmacSecret;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final VerificationCodeRepository verificationCodeRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final TeamService teamService;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final String dummyHash;

    public AuthServiceImpl(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            VerificationCodeRepository verificationCodeRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            TeamService teamService,
            GoogleIdTokenVerifier googleIdTokenVerifier,
            org.springframework.context.ApplicationEventPublisher eventPublisher
    ) {
        this.userRepository             = userRepository;
        this.refreshTokenRepository     = refreshTokenRepository;
        this.verificationCodeRepository = verificationCodeRepository;
        this.jwtService                 = jwtService;
        this.passwordEncoder            = passwordEncoder;
        this.teamService                = teamService;
        this.googleIdTokenVerifier      = googleIdTokenVerifier;
        this.eventPublisher             = eventPublisher;
        this.dummyHash                  = passwordEncoder.encode("dummy-password-for-timing");
    }

    // ── Register ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must not exceed 72 bytes");
        }
        
        String normalizedEmail = request.email().trim().toLowerCase();
        log.debug("Registering new user with email: {}", normalizedEmail);

        Optional<User> existing = userRepository.findByEmail(normalizedEmail);

        if (existing.isPresent()) {
            User user = existing.get();
            if (user.isEmailVerified()) {
                throw new IllegalStateException("An account with this email already exists.");
            }
            // Unverified squatter or a user who never finished signing up:
            // overwrite credentials so only the latest registrant (who must
            // still prove inbox control) ends up owning the account.
            user.setDisplayName(request.displayName().trim());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            userRepository.save(user);
            refreshTokenRepository.revokeAllByUserId(user.getId());
            issueAndSendCode(user, VerificationCodeType.EMAIL_VERIFICATION);
            log.info("Unverified account {} re-registered; credentials replaced", user.getId());
            return new RegisterResponse(user.getId(), user.getEmail(), false);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(request.displayName().trim(), normalizedEmail, passwordHash);
        User saved = userRepository.save(user);

        teamService.createTeam(saved.getId(), new CreateTeamRequest(saved.getDisplayName() + "'s Team"));
        log.info("Personal team created for user {}", saved.getId());

        issueAndSendCode(saved, VerificationCodeType.EMAIL_VERIFICATION);

        log.info("New user registered (pending verification): {} ({})", saved.getId(), saved.getEmail());

        return new RegisterResponse(saved.getId(), saved.getEmail(), saved.isEmailVerified());
    }

    // ── Login ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request, String deviceInfo) {
        String normalizedEmail = request.email() != null ? request.email().trim().toLowerCase() : "";
        log.debug("Login attempt for email: {}", normalizedEmail);

        Optional<User> userOpt = userRepository.findByEmail(normalizedEmail);

        if (userOpt.isEmpty()) {
            // Timing oracle mitigation: run BCrypt anyway to take identical duration
            passwordEncoder.matches(request.password(), dummyHash);
            throw new UnauthorizedAccessException("Invalid email or password.");
        }

        User user = userOpt.get();

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedAccessException("Invalid email or password.");
        }

        if (!user.isEmailVerified()) {
            throw new UnauthorizedAccessException(
                    "Please verify your email before logging in. Check your inbox for the code, " +
                            "or request a new one.");
        }

        log.info("User {} logged in successfully", user.getId());

        return issueTokenPair(user, deviceInfo);
    }

    @Override
    @Transactional
    public AuthResponse loginWithGoogle(GoogleLoginRequest request, String deviceInfo) {
        log.debug("Google login attempt");

        GoogleIdToken idToken;
        try {
            idToken = googleIdTokenVerifier.verify(request.idToken());
        } catch (Exception e) {
            log.warn("Google ID token verification failed: {}", e.getMessage());
            throw new UnauthorizedAccessException("Google authentication failed.");
        }
        if (idToken == null) {
            throw new UnauthorizedAccessException("Invalid Google ID Token.");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();

        Boolean emailVerified = payload.getEmailVerified();
        if (emailVerified == null || !emailVerified) {
            throw new UnauthorizedAccessException("Google account email is not verified.");
        }
        if (payload.getEmail() == null || payload.getEmail().isBlank()) {
            throw new UnauthorizedAccessException("Google account has no email.");
        }

        String email = payload.getEmail().trim().toLowerCase();
        Object rawName = payload.get("name");
        String name = (rawName instanceof String s && !s.isBlank())
                ? s.trim()
                : email.substring(0, email.indexOf('@') > 0 ? email.indexOf('@') : email.length());
        if (name.length() > 100) name = name.substring(0, 100);
        final String displayName = name;

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            log.info("Creating new user via Google: {}", email);
            User newUser = new User(displayName, email, passwordEncoder.encode(UUID.randomUUID().toString()));
            newUser.setEmailVerified(true);
            User saved = userRepository.save(newUser);
            teamService.createTeam(saved.getId(), new CreateTeamRequest(saved.getDisplayName() + "'s Team"));
            return saved;
        });

        if (!user.isEmailVerified()) {
            log.warn("Taking over unverified squatted account for email {} via Google login", email);
            user.setEmailVerified(true);
            user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
            userRepository.save(user);
            refreshTokenRepository.revokeAllByUserId(user.getId());
        }

        log.info("User {} logged in via Google", user.getId());
        return issueTokenPair(user, deviceInfo);
    }

    // ── Refresh ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(noRollbackFor = UnauthorizedAccessException.class)
    public AuthResponse refresh(RefreshRequest request, String deviceInfo) {
        String tokenHash = hashToken(request.refreshToken());
        RefreshToken storedToken = refreshTokenRepository
                .findByToken(tokenHash)
                .orElseThrow(() -> new UnauthorizedAccessException("Invalid refresh token."));

        if (storedToken.isRevoked()) {
            // Grace window for parallel network requests (30 seconds)
            if (storedToken.getUpdatedAt() != null &&
                Instant.now().minusSeconds(30).isBefore(storedToken.getUpdatedAt())) {
                throw new UnauthorizedAccessException("Invalid refresh token (parallel refresh detected).");
            }

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

        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        log.debug("Refresh token rotated for user {}", storedToken.getUser().getId());

        return issueTokenPair(storedToken.getUser(), deviceInfo);
    }

    // ── Logout ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        refreshTokenRepository.findByToken(hashToken(refreshToken)).ifPresent(t -> {
            t.setRevoked(true);
            refreshTokenRepository.save(t);
        });
    }

    // ── Email verification ─────────────────────────────────────────────────

    @Override
    @Transactional(noRollbackFor = InvalidCodeException.class)
    public AuthResponse verifyEmail(VerifyEmailRequest request, String deviceInfo) {
        String normalizedEmail = request.email() != null ? request.email().trim().toLowerCase() : "";
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new InvalidCodeException("Invalid or expired code."));

        if (user.isEmailVerified()) {
            throw new InvalidCodeException("Invalid or expired code.");
        }

        VerificationCode vc = verificationCodeRepository
                .findTopByUserIdAndTypeAndUsedFalseOrderByCreatedAtDesc(
                        user.getId(), VerificationCodeType.EMAIL_VERIFICATION)
                .orElseThrow(() -> new InvalidCodeException("Invalid or expired code."));

        if (!vc.isValid(hashCode(user.getId(), VerificationCodeType.EMAIL_VERIFICATION, request.code()))) {
            vc.setAttempts(vc.getAttempts() + 1);
            if (vc.getAttempts() >= 5) vc.setUsed(true);
            verificationCodeRepository.save(vc);
            throw new InvalidCodeException("Invalid or expired code.");
        }

        vc.setUsed(true);
        verificationCodeRepository.save(vc);

        user.setEmailVerified(true);
        userRepository.save(user);

        log.info("Email verified for user {}", user.getId());

        return issueTokenPair(user, deviceInfo);
    }

    @Override
    @Transactional
    public void resendVerificationCode(ResendVerificationRequest request) {
        String normalizedEmail = request.email() != null ? request.email().trim().toLowerCase() : "";
        Optional<User> maybeUser = userRepository.findByEmail(normalizedEmail);
        if (maybeUser.isEmpty() || maybeUser.get().isEmailVerified()) {
            return; // silent no-op — don't reveal account existence or verified state
        }
        issueAndSendCode(maybeUser.get(), VerificationCodeType.EMAIL_VERIFICATION);
    }

    // ── Password reset ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String normalizedEmail = request.email() != null ? request.email().trim().toLowerCase() : "";
        userRepository.findByEmail(normalizedEmail)
                .ifPresent(user -> issueAndSendCode(user, VerificationCodeType.PASSWORD_RESET));
    }

    @Override
    @Transactional(noRollbackFor = InvalidCodeException.class)
    public void resetPassword(ResetPasswordRequest request) {
        if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must not exceed 72 bytes");
        }
        String normalizedEmail = request.email() != null ? request.email().trim().toLowerCase() : "";
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new InvalidCodeException("Invalid or expired code."));

        VerificationCode vc = verificationCodeRepository
                .findTopByUserIdAndTypeAndUsedFalseOrderByCreatedAtDesc(
                        user.getId(), VerificationCodeType.PASSWORD_RESET)
                .orElseThrow(() -> new InvalidCodeException("Invalid or expired code."));

        if (!vc.isValid(hashCode(user.getId(), VerificationCodeType.PASSWORD_RESET, request.code()))) {
            vc.setAttempts(vc.getAttempts() + 1);
            if (vc.getAttempts() >= 5) vc.setUsed(true);
            verificationCodeRepository.save(vc);
            throw new InvalidCodeException("Invalid or expired code.");
        }

        vc.setUsed(true);
        verificationCodeRepository.save(vc);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        refreshTokenRepository.revokeAllByUserId(user.getId());

        log.info("Password reset completed for user {}", user.getId());
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private AuthResponse issueTokenPair(User user, String deviceInfo) {
        String accessToken = jwtService.generateAccessToken(user.getId());

        String rawRefreshToken = UUID.randomUUID().toString();
        String tokenHash       = hashToken(rawRefreshToken);
        Instant refreshExpiry  = Instant.now().plusSeconds(refreshTokenExpiryDays * 86_400);

        RefreshToken refreshToken = new RefreshToken(
                tokenHash,
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

    /** Plain SHA-256 hex — used only for high-entropy refresh tokens. */
    private String hashToken(String rawToken) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Keyed hash for low-entropy 6-digit codes. Bound to user and code type
     * so a stolen hash cannot be replayed against another account or flow.
     */
    private String hashCode(UUID userId, VerificationCodeType type, String rawCode) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(codeHmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String message = userId + ":" + type.name() + ":" + (rawCode == null ? "" : rawCode);
            return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void issueAndSendCode(User user, VerificationCodeType type) {
        verificationCodeRepository.invalidateAllForUser(user.getId(), type);

        String rawCode = generateSixDigitCode();
        String hashedCode = hashCode(user.getId(), type, rawCode);
        Instant expiresAt = Instant.now().plusSeconds(codeExpiryMinutes * 60L);

        VerificationCode vc = new VerificationCode(user, hashedCode, type, expiresAt);
        verificationCodeRepository.save(vc);

        var emailType = type == VerificationCodeType.EMAIL_VERIFICATION
                ? com.taskmanager.core.event.SendEmailEvent.EmailType.VERIFICATION_CODE
                : com.taskmanager.core.event.SendEmailEvent.EmailType.PASSWORD_RESET_CODE;

        eventPublisher.publishEvent(new com.taskmanager.core.event.SendEmailEvent(
                user.getEmail(), user.getDisplayName(), rawCode, emailType));
    }

    private String generateSixDigitCode() {
        int number = RANDOM.nextInt(1_000_000); // 0 – 999999
        return String.format("%06d", number);
    }
}