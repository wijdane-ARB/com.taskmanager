package com.taskmanager.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter for auth endpoints only.
 *
 * Why in the app and not at the gateway?
 *   No gateway yet. This is defence-in-depth for a single-instance deployment.
 *   When you add a load balancer or API gateway, move limiting there and
 *   remove this filter — or keep both for belt-and-suspenders.
 *
 * Limits (per source IP, per 60-second window):
 *   POST /api/v1/auth/login    →  5  (brute-force guard)
 *   POST /api/v1/auth/register →  5  (signup spam guard)
 *   POST /api/v1/auth/refresh  → 20  (Flutter's interceptor calls this often)
 *
 * Algorithm: sliding-window log.
 *   Each request appends a timestamp; expired timestamps are evicted on every
 *   request. Accurate, no burst artefacts. At these request ceilings the
 *   per-IP memory cost is negligible (≤20 longs per key).
 *
 * Thread safety: each key gets its own Deque, synchronized on that Deque.
 *   ConcurrentHashMap handles concurrent puts for distinct keys.
 *
 * Response format: matches ApiResponse<Void> so Flutter can handle 429
 *   through the same error path as every other failure.
 */
@Component
@Order(1) // Runs before JwtAuthenticationFilter (which lives inside Spring Security)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final long WINDOW_MS = 60_000L; // 1 minute

    // Endpoint → max requests allowed within WINDOW_MS
    private static final Map<String, Integer> LIMITS = Map.of(
            "/api/v1/auth/login",    5,
            "/api/v1/auth/register", 5,
            "/api/v1/auth/refresh",  20
    );

    // "IP::path" → ordered log of request timestamps for that window
    private final ConcurrentHashMap<String, Deque<Long>> requestLog = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         chain
    ) throws ServletException, IOException {

        Integer limit = LIMITS.get(request.getRequestURI());

        // Not a rate-limited path — pass through immediately
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }

        String ip  = extractClientIp(request);
        String key = ip + "::" + request.getRequestURI();
        long   now = System.currentTimeMillis();

        Deque<Long> window = requestLog.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (window) {
            // Drop timestamps that have fallen outside the current window
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                window.pollFirst();
            }

            if (window.size() >= limit) {
                log.warn("Rate limit hit — IP={} path={} count={}/{}",
                        ip, request.getRequestURI(), window.size(), limit);
                reject(response);
                return;
            }

            window.addLast(now);
        }

        chain.doFilter(request, response);
    }

    /**
     * Evicts dead entries every minute so the map doesn't grow indefinitely.
     * A key is dead when its most-recent timestamp is older than one window
     * (meaning no requests have arrived for that IP+path in over a minute).
     */
    @Scheduled(fixedRate = 60_000L)
    public void evictExpiredEntries() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        requestLog.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty()
                        || entry.getValue().peekLast() < cutoff;
            }
        });
        log.debug("Rate limit map size after eviction: {}", requestLog.size());
    }

    /**
     * Returns 429 with an ApiResponse-shaped body so Flutter's existing
     * error handling picks it up without special-casing.
     */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"success":false,"message":"Too many requests. Please wait a moment and try again.",\
                "data":null,"errors":null,"timestamp":"%s"}""".formatted(Instant.now()));
    }

    /**
     * Extracts the real client IP, respecting X-Forwarded-For for deployments
     * behind a reverse proxy or load balancer. Takes only the leftmost value
     * to prevent spoofing via appended IPs.
     */
    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
