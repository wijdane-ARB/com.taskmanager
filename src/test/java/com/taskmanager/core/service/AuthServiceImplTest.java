package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.LoginRequest;
import com.taskmanager.core.dto.request.RefreshRequest;
import com.taskmanager.core.dto.request.RegisterRequest;
import com.taskmanager.core.dto.response.AuthResponse;
import com.taskmanager.core.entity.RefreshToken;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.exception.UnauthorizedAccessException;
import com.taskmanager.core.repository.RefreshTokenRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.security.JwtService;
import com.taskmanager.core.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthServiceImpl")
class AuthServiceImplTest {

    @Mock UserRepository           userRepository;
    @Mock RefreshTokenRepository   refreshTokenRepository;
    @Mock JwtService               jwtService;
    @Mock PasswordEncoder          passwordEncoder;

    @InjectMocks AuthServiceImpl authService;

    private static final String DEVICE = "Test-Agent/1.0";
    private static final String ACCESS  = "access.token.value";
    private static final long   EXPIRY  = 900L;

    @BeforeEach
    void stubJwt() {
        lenient().when(jwtService.generateAccessToken(any())).thenReturn(ACCESS);
        lenient().when(jwtService.getAccessTokenExpirySeconds()).thenReturn(EXPIRY);
        // inject @Value field that Spring would normally set
        ReflectionTestUtils.setField(authService, "refreshTokenExpiryDays", 30L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User makeUser(String email) {
        User u = new User("Test User", email, "hashed");
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    private RefreshToken makeToken(User user, boolean revoked, boolean expired) {
        Instant expiry = expired
                ? Instant.now().minusSeconds(3600)
                : Instant.now().plusSeconds(86_400);
        RefreshToken rt = new RefreshToken("raw-token-value", user, expiry, DEVICE);
        rt.setRevoked(revoked);
        return rt;
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Nested @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("creates user and returns token pair on success")
        void success() {
            RegisterRequest req = new RegisterRequest("Alice", "alice@example.com", "P@ssword1");
            User saved = makeUser("alice@example.com");

            when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
            when(passwordEncoder.encode("P@ssword1")).thenReturn("hashed");
            when(userRepository.save(any(User.class))).thenReturn(saved);
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AuthResponse resp = authService.register(req, DEVICE);

            assertThat(resp.accessToken()).isEqualTo(ACCESS);
            assertThat(resp.accessTokenExpiresInSeconds()).isEqualTo(EXPIRY);
            assertThat(resp.email()).isEqualTo("alice@example.com");

            // password must be encoded, never stored raw
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed");
        }

        @Test
        @DisplayName("throws IllegalStateException when email already registered")
        void duplicateEmail() {
            RegisterRequest req = new RegisterRequest("Bob", "bob@example.com", "P@ssword1");
            when(userRepository.existsByEmail("bob@example.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(req, DEVICE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already exists");

            verify(userRepository, never()).save(any());
        }
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Nested @DisplayName("login()")
    class Login {

        @Test
        @DisplayName("returns token pair for valid credentials")
        void success() {
            User user = makeUser("carol@example.com");
            LoginRequest req = new LoginRequest("carol@example.com", "correct");

            when(userRepository.findByEmail("carol@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
            when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AuthResponse resp = authService.login(req, DEVICE);

            assertThat(resp.accessToken()).isEqualTo(ACCESS);
        }

        @Test
        @DisplayName("throws UnauthorizedAccessException for unknown email")
        void unknownEmail() {
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest("x@x.com", "pw"), DEVICE))
                    .isInstanceOf(UnauthorizedAccessException.class)
                    // must NOT reveal whether the email exists
                    .hasMessage("Invalid email or password.");
        }

        @Test
        @DisplayName("throws UnauthorizedAccessException for wrong password")
        void wrongPassword() {
            User user = makeUser("dave@example.com");
            when(userRepository.findByEmail("dave@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(false);

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest("dave@example.com", "wrong"), DEVICE))
                    .isInstanceOf(UnauthorizedAccessException.class)
                    // same message — no email enumeration
                    .hasMessage("Invalid email or password.");
        }

        @Test
        @DisplayName("wrong-password error message is identical to unknown-email message")
        void noEmailEnumeration() {
            // Both failure paths must return the exact same string so an attacker
            // cannot distinguish "email not found" from "password wrong".
            User user = makeUser("e@e.com");
            when(userRepository.findByEmail("e@e.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(false);
            when(userRepository.findByEmail("noone@e.com")).thenReturn(Optional.empty());

            String wrongPw = catchThrowableOfType(
                    () -> authService.login(new LoginRequest("e@e.com", "bad"), DEVICE),
                    UnauthorizedAccessException.class
            ).getMessage();

            String noUser = catchThrowableOfType(
                    () -> authService.login(new LoginRequest("noone@e.com", "bad"), DEVICE),
                    UnauthorizedAccessException.class
            ).getMessage();

            assertThat(wrongPw).isEqualTo(noUser);
        }
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Nested @DisplayName("refresh()")
    class Refresh {

        @Test
        @DisplayName("issues new token pair and revokes old refresh token")
        void rotatesToken() {
            User user = makeUser("eve@example.com");
            RefreshToken old = makeToken(user, false, false);
            when(refreshTokenRepository.findByToken("raw-token-value"))
                    .thenReturn(Optional.of(old));
            when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            authService.refresh(new RefreshRequest("raw-token-value"), DEVICE);

            // old token must be revoked
            assertThat(old.isRevoked()).isTrue();
            // a new refresh token must be persisted (2 saves: revoke old + save new)
            verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("throws on expired refresh token")
        void expiredToken() {
            User user = makeUser("f@example.com");
            RefreshToken expired = makeToken(user, false, true);
            when(refreshTokenRepository.findByToken("raw-token-value"))
                    .thenReturn(Optional.of(expired));

            assertThatThrownBy(() ->
                    authService.refresh(new RefreshRequest("raw-token-value"), DEVICE))
                    .isInstanceOf(UnauthorizedAccessException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("throws on unknown token")
        void unknownToken() {
            when(refreshTokenRepository.findByToken(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    authService.refresh(new RefreshRequest("ghost-token"), DEVICE))
                    .isInstanceOf(UnauthorizedAccessException.class);
        }

        // ── Token theft detection ─────────────────────────────────────────────

        @Test
        @DisplayName("SECURITY: reusing a revoked token nukes all sessions for that user")
        void tokenTheftRevokesAllSessions() {
            User user = makeUser("victim@example.com");
            RefreshToken stolen = makeToken(user, true, false); // already revoked = was rotated
            when(refreshTokenRepository.findByToken("stolen-token"))
                    .thenReturn(Optional.of(stolen));

            assertThatThrownBy(() ->
                    authService.refresh(new RefreshRequest("stolen-token"), DEVICE))
                    .isInstanceOf(UnauthorizedAccessException.class)
                    .hasMessageContaining("Security alert");

            // ALL sessions for the victim must be terminated immediately
            verify(refreshTokenRepository).revokeAllByUserId(user.getId());
        }

        @Test
        @DisplayName("SECURITY: theft detection does NOT issue a new token pair")
        void tokenTheftIssuesNoNewToken() {
            User user = makeUser("victim2@example.com");
            RefreshToken stolen = makeToken(user, true, false);
            when(refreshTokenRepository.findByToken("stolen-token"))
                    .thenReturn(Optional.of(stolen));

            catchThrowableOfType(
                    () -> authService.refresh(new RefreshRequest("stolen-token"), DEVICE),
                    UnauthorizedAccessException.class
            );

            // no new access token should be generated under any circumstance
            verify(jwtService, never()).generateAccessToken(any());
        }
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Nested @DisplayName("logout()")
    class Logout {

        @Test
        @DisplayName("revokes all refresh tokens for the user")
        void revokesAll() {
            UUID userId = UUID.randomUUID();
            authService.logout(userId);
            verify(refreshTokenRepository).revokeAllByUserId(userId);
        }
    }
}
