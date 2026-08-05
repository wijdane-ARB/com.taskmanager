package com.taskmanager.core.service.impl;

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
import com.taskmanager.core.service.EmailService;
import com.taskmanager.core.service.TeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${jwt.refresh-token-expiry-days:30}")
    private long refreshTokenExpiryDays;

    @Value("${app.mail.code-expiry-minutes:15}")
    private int codeExpiryMinutes;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final VerificationCodeRepository verificationCodeRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final TeamService teamService;   // restored

    public AuthServiceImpl(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            VerificationCodeRepository verificationCodeRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            EmailService emailService,
            TeamService teamService           // restored
    ) {
        this.userRepository             = userRepository;
        this.refreshTokenRepository     = refreshTokenRepository;
        this.verificationCodeRepository = verificationCodeRepository;
        this.jwtService                 = jwtService;
        this.passwordEncoder            = passwordEncoder;
        this.emailService               = emailService;
        this.teamService                = teamService;
    }

    // ── Register ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        log.debug("Registering new user with email: {}", request.email());

        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalStateException("An account with this email already exists.");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(request.displayName(), request.email(), passwordHash);
        User saved = userRepository.save(user);

        // ── Create a personal team for the user ────────────────────────────────
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
        log.debug("Login attempt for email: {}", request.email());

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedAccessException("Invalid email or password."));

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

    // ── Refresh ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AuthResponse refresh(RefreshRequest request, String deviceInfo) {
        RefreshToken storedToken = refreshTokenRepository
                .findByToken(request.refreshToken())
                .orElseThrow(() -> new UnauthorizedAccessException("Invalid refresh token."));

        if (storedToken.isRevoked()) {
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
    public void logout(UUID userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
        log.info("All refresh tokens revoked for user {}", userId);
    }

    // ── Email verification ─────────────────────────────────────────────────

    @Override
    @Transactional
    public AuthResponse verifyEmail(VerifyEmailRequest request, String deviceInfo) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCodeException("Invalid or expired code."));

        if (user.isEmailVerified()) {
            // Already verified (e.g. double-submit) — log them in rather
            // than erroring, since the account is already in the target state.
            return issueTokenPair(user, deviceInfo);
        }

        VerificationCode vc = verificationCodeRepository
                .findTopByUserIdAndTypeAndUsedFalseOrderByCreatedAtDesc(
                        user.getId(), VerificationCodeType.EMAIL_VERIFICATION)
                .orElseThrow(() -> new InvalidCodeException("Invalid or expired code."));

        if (!vc.isValid(request.code())) {
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
        Optional<User> maybeUser = userRepository.findByEmail(request.email());
        if (maybeUser.isEmpty() || maybeUser.get().isEmailVerified()) {
            return; // silent no-op — don't reveal account existence or verified state
        }
        issueAndSendCode(maybeUser.get(), VerificationCodeType.EMAIL_VERIFICATION);
    }

    // ── Password reset ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.email())
                .ifPresent(user -> issueAndSendCode(user, VerificationCodeType.PASSWORD_RESET));
        // No branch on absence — outward behaviour is identical either way.
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCodeException("Invalid or expired code."));

        VerificationCode vc = verificationCodeRepository
                .findTopByUserIdAndTypeAndUsedFalseOrderByCreatedAtDesc(
                        user.getId(), VerificationCodeType.PASSWORD_RESET)
                .orElseThrow(() -> new InvalidCodeException("Invalid or expired code."));

        if (!vc.isValid(request.code())) {
            throw new InvalidCodeException("Invalid or expired code.");
        }

        vc.setUsed(true);
        verificationCodeRepository.save(vc);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Password changed — kill every existing session, including
        // whatever an attacker may have been using.
        refreshTokenRepository.revokeAllByUserId(user.getId());

        log.info("Password reset completed for user {}", user.getId());
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private AuthResponse issueTokenPair(User user, String deviceInfo) {
        String accessToken = jwtService.generateAccessToken(user.getId());

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

    /**
     * Generates a fresh 6-digit code, invalidates any previous outstanding
     * code of the same type for this user, persists the new one, and emails it.
     */
    private void issueAndSendCode(User user, VerificationCodeType type) {
        verificationCodeRepository.invalidateAllForUser(user.getId(), type);

        String code = generateSixDigitCode();
        Instant expiresAt = Instant.now().plusSeconds(codeExpiryMinutes * 60L);

        VerificationCode vc = new VerificationCode(user, code, type, expiresAt);
        verificationCodeRepository.save(vc);

        if (type == VerificationCodeType.EMAIL_VERIFICATION) {
            emailService.sendVerificationCode(user.getEmail(), user.getDisplayName(), code);
        } else {
            emailService.sendPasswordResetCode(user.getEmail(), user.getDisplayName(), code);
        }
    }

    private String generateSixDigitCode() {
        int number = RANDOM.nextInt(1_000_000); // 0 – 999999
        return String.format("%06d", number);
    }
}