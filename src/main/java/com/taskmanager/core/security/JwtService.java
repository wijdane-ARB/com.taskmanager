package com.taskmanager.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Responsible for issuing and validating JWT access tokens only.
 * Refresh tokens are opaque UUIDs stored in the DB — see RefreshToken entity.
 *
 * The access token payload (claims):
 *   sub  → user UUID (used by @AuthenticationPrincipal in all controllers)
 *   iat  → issued-at timestamp
 *   exp  → expiry timestamp (15 minutes for mobile; adjust via config)
 *
 * Algorithm: HS256 with a 256-bit key derived from the configured secret.
 * For production, prefer RS256 with a key pair so other services can verify
 * tokens without sharing the signing secret.
 *
 * Required application.properties entries:
 *   jwt.secret=<at-least-32-character-random-string>
 *   jwt.access-token-expiry-seconds=900
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenExpirySeconds;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry-seconds:900}") long accessTokenExpirySeconds
    ) {
        // Keys.hmacShaKeyFor requires at least 32 bytes for HS256
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirySeconds = accessTokenExpirySeconds;
    }

    /**
     * Issues a signed JWT access token for the given user ID.
     * The subject is the user's UUID string — extracted by the JWT filter
     * and injected as @AuthenticationPrincipal UUID in all controllers.
     */
    public String generateAccessToken(UUID userId) {
        Instant now    = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenExpirySeconds);

        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validates the token signature and expiry, then returns the subject (user UUID).
     * Throws JwtException (a RuntimeException) if the token is invalid or expired —
     * caught by the JWT filter which returns 401.
     */
    public UUID extractUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return UUID.fromString(claims.getSubject());
    }

    /**
     * Returns true only if the token is structurally valid AND not expired.
     * Used by the JWT filter — false means the request continues unauthenticated.
     */
    public boolean isTokenValid(String token) {
        try {
            extractUserId(token); // throws on invalid or expired
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long getAccessTokenExpirySeconds() {
        return accessTokenExpirySeconds;
    }
}
