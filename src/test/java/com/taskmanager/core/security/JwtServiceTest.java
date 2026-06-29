package com.taskmanager.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JwtService")
class JwtServiceTest {

    // 32+ char secret required for HS256
    private static final String SECRET = "test-secret-key-must-be-32-chars!!";
    private static final long   EXPIRY = 900L;

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, EXPIRY);
    }

    @Test
    @DisplayName("generated token is valid immediately after issuance")
    void generatedTokenIsValid() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId);
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    @DisplayName("extractUserId returns the correct UUID from a valid token")
    void extractsUserId() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId);
        assertThat(jwtService.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    @DisplayName("isTokenValid returns false for a tampered token")
    void tamperedToken() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId);
        // flip one character in the signature segment
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThat(jwtService.isTokenValid(tampered)).isFalse();
    }

    @Test
    @DisplayName("isTokenValid returns false for a token signed with a different secret")
    void wrongSecret() {
        JwtService other = new JwtService("completely-different-secret-key!!", EXPIRY);
        String token = other.generateAccessToken(UUID.randomUUID());
        assertThat(jwtService.isTokenValid(token)).isFalse();
    }

    @Test
    @DisplayName("isTokenValid returns false for an expired token")
    void expiredToken() {
        // TTL of -1 second → already expired at issuance
        JwtService expiredService = new JwtService(SECRET, -1L);
        String token = expiredService.generateAccessToken(UUID.randomUUID());
        assertThat(jwtService.isTokenValid(token)).isFalse();
    }

    @Test
    @DisplayName("isTokenValid returns false for a blank string")
    void blankToken() {
        assertThat(jwtService.isTokenValid("")).isFalse();
        assertThat(jwtService.isTokenValid("   ")).isFalse();
    }

    @Test
    @DisplayName("isTokenValid returns false for a random non-JWT string")
    void randomString() {
        assertThat(jwtService.isTokenValid("not.a.jwt")).isFalse();
    }

    @Test
    @DisplayName("getAccessTokenExpirySeconds returns the configured value")
    void expirySeconds() {
        assertThat(jwtService.getAccessTokenExpirySeconds()).isEqualTo(EXPIRY);
    }

    @Test
    @DisplayName("two users get different tokens")
    void tokensAreUserSpecific() {
        String t1 = jwtService.generateAccessToken(UUID.randomUUID());
        String t2 = jwtService.generateAccessToken(UUID.randomUUID());
        assertThat(t1).isNotEqualTo(t2);
    }
}
