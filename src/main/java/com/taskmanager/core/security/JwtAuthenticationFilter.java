package com.taskmanager.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Runs once per request (OncePerRequestFilter guarantees this even in async dispatch).
 *
 * Flow:
 *   1. Extract "Bearer <token>" from the Authorization header.
 *   2. Validate the token signature and expiry via JwtService.
 *   3. Extract the user UUID from the verified claims.
 *   4. Set authentication in SecurityContext so Spring Security
 *      treats this request as authenticated.
 *
 * ── Why we removed the existsById() DB call ───────────────────────────────
 * The original filter called userRepository.existsById(userId) on EVERY
 * authenticated request to guard against "deleted account but valid token".
 * This added a DB round-trip to every API call — roughly 1–5 ms each, plus
 * connection pool contention under load.
 *
 * The deleted-account edge case is already handled adequately by:
 *   a) The 15-minute JWT TTL: stale tokens expire quickly on their own.
 *   b) Service-layer lookups: every write path calls findUserOrThrow(),
 *      which throws 404 if the account is gone.
 *   c) For immediate revocation: add a Redis blocklist keyed on jti
 *      (JWT ID) if this becomes a security requirement. A Redis GET is
 *      ~0.1 ms vs ~3 ms for a Postgres query — a 30× improvement.
 *
 * The principal is the raw UUID — resolved by @AuthenticationPrincipal
 * in every controller without further casting.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // Fast-path: no auth header → continue unauthenticated
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String token = authHeader.substring(7); // strip "Bearer "

        if (!jwtService.isTokenValid(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        UUID userId = jwtService.extractUserId(token);

        // Only set auth if the SecurityContext is empty (avoid double-processing)
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))
                    );
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }
}