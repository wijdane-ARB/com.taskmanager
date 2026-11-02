package com.taskmanager.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Changes in this version:
 *
 * LOCKOUT FIX: the old account bucket was keyed on email alone with a tight
 * limit (3 logins / 15 min), so anyone could lock a victim out of their own
 * account just by sending 3 bad logins for that email. The tight per-account
 * bucket is now keyed on email + caller IP, so an attacker only exhausts their
 * own budget. A second, looser email-only bucket (ACCOUNT_GLOBAL_LIMITS) still
 * caps total guesses against one account across rotating IPs. Trade-off: an
 * attacker with many IPs can still burn that loose bucket; for a stronger
 * answer add CAPTCHA / progressive delay after N failures.
 *
 * COVERAGE FIX: /auth/google and /auth/logout now have an IP limit.
 *
 * The refund of a token on 2xx now applies to every account bucket consumed.
 *
 * Reading the body requires CachedBodyHttpServletRequest so Spring MVC can
 * still bind @RequestBody downstream.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final LettuceBasedProxyManager<byte[]> proxyManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    @Value("${app.trusted-proxy-hops:1}")
    private int trustedProxyHops;

    private static final Map<String, Integer> IP_LIMITS = Map.ofEntries(
            Map.entry("/api/v1/auth/login",               5),
            Map.entry("/api/v1/auth/register",            5),
            Map.entry("/api/v1/auth/refresh",             20),
            Map.entry("/api/v1/auth/verify-email",        10),
            Map.entry("/api/v1/auth/resend-verification", 3),
            Map.entry("/api/v1/auth/forgot-password",     3),
            Map.entry("/api/v1/auth/reset-password",      5),
            Map.entry("/api/v1/auth/google",              10),
            Map.entry("/api/v1/auth/logout",              20)
    );

    /** Tight limit per (email + IP) per 15 min — attacker only burns their own budget. */
    private static final Map<String, Integer> ACCOUNT_IP_LIMITS = Map.of(
            "/api/v1/auth/login",               5,
            "/api/v1/auth/register",            3,
            "/api/v1/auth/verify-email",        5,
            "/api/v1/auth/resend-verification", 2,
            "/api/v1/auth/forgot-password",     2,
            "/api/v1/auth/reset-password",      3
    );

    /** Loose limit per email alone per 15 min — caps distributed guessing. */
    private static final Map<String, Integer> ACCOUNT_GLOBAL_LIMITS = Map.of(
            "/api/v1/auth/login",               30,
            "/api/v1/auth/register",            10,
            "/api/v1/auth/verify-email",        20,
            "/api/v1/auth/resend-verification", 6,
            "/api/v1/auth/forgot-password",     6,
            "/api/v1/auth/reset-password",      12
    );

    private static final Duration IP_WINDOW      = Duration.ofMinutes(1);
    private static final Duration ACCOUNT_WINDOW = Duration.ofMinutes(15);

    public RateLimitFilter(LettuceBasedProxyManager<byte[]> proxyManager) {
        this.proxyManager = proxyManager;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        String rawUri = request.getRequestURI();
        String uri = rawUri.endsWith("/") && rawUri.length() > 1 ? rawUri.substring(0, rawUri.length() - 1) : rawUri;
        Integer ipLimit = IP_LIMITS.get(uri);

        boolean isInvite = uri.startsWith("/api/v1/teams/") && uri.endsWith("/invites");
        if (isInvite) {
            ipLimit = 10;
        }

        if (ipLimit == null) {
            chain.doFilter(request, response);
            return;
        }

        String ip = extractClientIp(request);
        String ipKey = ip + "::" + uri;

        try {
            Bucket ipBucket = getBucket(ipKey, ipLimit, IP_WINDOW);
            if (!ipBucket.tryConsume(1)) {
                log.warn("IP rate limit hit — key={} path={} limit={}/min", ipKey, uri, ipLimit);
                reject(response);
                return;
            }
        } catch (Exception e) {
            log.error("Redis rate limiter unavailable on IP check — failing open: {}", e.getMessage());
        }

        if (request.getContentLength() > 8192) {
            rejectPayloadTooLarge(response);
            return;
        }

        CachedBodyHttpServletRequest wrappedRequest;
        try {
            wrappedRequest = new CachedBodyHttpServletRequest(request);
        } catch (IllegalArgumentException e) {
            rejectPayloadTooLarge(response);
            return;
        }

        Integer accountIpLimit = ACCOUNT_IP_LIMITS.get(uri);
        Integer accountGlobalLimit = ACCOUNT_GLOBAL_LIMITS.get(uri);
        if (isInvite) {
            accountIpLimit = 5;
            accountGlobalLimit = null;
        }

        if (accountIpLimit == null && accountGlobalLimit == null) {
            chain.doFilter(wrappedRequest, response);
            return;
        }

        List<Bucket> consumed = new ArrayList<>();
        String email = extractEmail(wrappedRequest);
        if (email != null) {
            String normalized = email.trim().toLowerCase();
            try {
                if (accountIpLimit != null) {
                    Bucket b = getBucket("account-ip::" + normalized + "::" + ip + "::" + uri,
                            accountIpLimit, ACCOUNT_WINDOW);
                    if (!b.tryConsume(1)) {
                        log.warn("Account+IP rate limit hit — path={} limit={}/{}min",
                                uri, accountIpLimit, ACCOUNT_WINDOW.toMinutes());
                        reject(response);
                        return;
                    }
                    consumed.add(b);
                }
                if (accountGlobalLimit != null) {
                    Bucket b = getBucket("account::" + normalized + "::" + uri,
                            accountGlobalLimit, ACCOUNT_WINDOW);
                    if (!b.tryConsume(1)) {
                        log.warn("Account rate limit hit — path={} limit={}/{}min",
                                uri, accountGlobalLimit, ACCOUNT_WINDOW.toMinutes());
                        reject(response);
                        return;
                    }
                    consumed.add(b);
                }
            } catch (Exception e) {
                log.error("Redis rate limiter unavailable on Account check — failing open: {}", e.getMessage());
            }
        }

        chain.doFilter(wrappedRequest, response);

        try {
            boolean refundable = uri.endsWith("/login") || uri.endsWith("/verify-email");
            if (refundable && response.getStatus() >= 200 && response.getStatus() < 300) {
                for (Bucket b : consumed) {
                    b.addTokens(1);
                }
            }
        } catch (Exception e) {
            log.error("Failed to refund token to account bucket: {}", e.getMessage());
        }
    }

    private Bucket getBucket(String key, int limit, Duration window) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(limit, Refill.intervally(limit, window)))
                .build();

        return proxyManager.builder().build(key.getBytes(StandardCharsets.UTF_8), configuration);
    }

    /** Best-effort "email" extraction; never throws and never logs the body. */
    private String extractEmail(CachedBodyHttpServletRequest wrapped) {
        try {
            byte[] body = wrapped.getCachedBody();
            if (body.length == 0) return null;

            JsonNode node = objectMapper.readTree(body);
            JsonNode emailNode = node.get("email");
            if (emailNode == null || emailNode.isNull()) return null;

            String email = emailNode.asText();
            return email.isBlank() ? null : email;
        } catch (Exception e) {
            return null;
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"success":false,"message":"Too many requests. Please wait a moment and try again.",\
                "data":null,"errors":null,"timestamp":"%s"}""".formatted(Instant.now()));
    }

    private void rejectPayloadTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"success":false,"message":"Payload Too Large",\
                "data":null,"errors":null,"timestamp":"%s"}""".formatted(Instant.now()));
    }

    /**
     * Only trusts X-Forwarded-For if app.trust-proxy-headers=true, which should
     * only be set once the reverse proxy overwrites (not appends to) any
     * client-supplied value. Otherwise falls back to request.getRemoteAddr().
     * (application.yml already has the property at app.trust-proxy-headers,
     * so the old YAML-placement note no longer applies.)
     */
    private String extractClientIp(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] ips = forwarded.split(",");
                if (ips.length >= trustedProxyHops) {
                    return ips[ips.length - trustedProxyHops].trim();
                }
            }
        }
        return request.getRemoteAddr();
    }
}