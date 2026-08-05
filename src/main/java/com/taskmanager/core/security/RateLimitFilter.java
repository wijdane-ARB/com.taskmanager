// FILE: RateLimitFilter.java
package com.taskmanager.core.security;

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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * SECURITY FIX: extractClientIp() previously trusted X-Forwarded-For
 * unconditionally. Unless this app sits behind a reverse proxy that
 * OVERWRITES (not appends to) that header, any client can spoof it —
 * e.g. send X-Forwarded-For: 1.2.3.4 on every request to get a fresh
 * rate-limit bucket per request, fully defeating login/register/reset
 * throttling (which exist specifically to slow credential stuffing and
 * verification-code brute force).
 *
 * Fix: X-Forwarded-For is now only trusted when app.trust-proxy-headers=true,
 * which you should only enable if you've verified your proxy strips any
 * client-supplied X-Forwarded-For before appending its own. Default is false
 * — safe-by-default for anyone who deploys this without a proxy in front.
 *
 * Also added a per-account key for the auth endpoints, since IP-only limits
 * are trivially bypassed by rotating IPs (which a real attacker with a
 * botnet or proxy pool will do) regardless of the spoofing issue.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final LettuceBasedProxyManager<byte[]> proxyManager;

    @Value("${app.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    private static final Map<String, Integer> LIMITS = Map.of(
            "/api/v1/auth/login",               5,
            "/api/v1/auth/register",            5,
            "/api/v1/auth/refresh",             20,
            "/api/v1/auth/verify-email",        10,
            "/api/v1/auth/resend-verification", 3,
            "/api/v1/auth/forgot-password",     3,
            "/api/v1/auth/reset-password",      5
    );

    public RateLimitFilter(LettuceBasedProxyManager<byte[]> proxyManager) {
        this.proxyManager = proxyManager;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        Integer limit = LIMITS.get(request.getRequestURI());

        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }

        String ip = extractClientIp(request);
        String key = ip + "::" + request.getRequestURI();

        if (!tryConsume(key, limit)) {
            log.warn("Rate limit hit — key={} path={} limit={}/min", key, request.getRequestURI(), limit);
            reject(response);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean tryConsume(String key, int limit) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(limit, Refill.intervally(limit, Duration.ofMinutes(1))))
                .build();

        Bucket bucket = proxyManager.builder().build(key.getBytes(), configuration);
        return bucket.tryConsume(1);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"success":false,"message":"Too many requests. Please wait a moment and try again.",\
                "data":null,"errors":null,"timestamp":"%s"}""".formatted(Instant.now()));
    }

    /**
     * Only trusts X-Forwarded-For if app.trust-proxy-headers=true, which
     * should only be set once you've confirmed the reverse proxy overwrites
     * (not appends to) any client-supplied value. Otherwise falls back to
     * request.getRemoteAddr(), which the client cannot forge — it's the
     * actual TCP peer address as seen by the servlet container.
     */
    private String extractClientIp(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}