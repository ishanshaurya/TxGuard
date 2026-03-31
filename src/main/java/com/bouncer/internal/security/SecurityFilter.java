package com.bouncer.internal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single filter handling three concerns:
 *
 * 1. API Key Authentication — X-API-Key header required on /api/** routes.
 *    Missing or wrong key → 401 Unauthorized.
 *
 * 2. Rate Limiting — sliding window per API key.
 *    Exceeding limit → 429 Too Many Requests.
 *
 * 3. Active Request Tracking — increments/decrements the active
 *    request gauge so graceful shutdown can be observed live.
 *
 * Actuator endpoints (/actuator/**) and the dashboard (/) are excluded.
 */
@Component
public class SecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final int    RATE_LIMIT     = 30;  // requests per window
    private static final long   WINDOW_MS      = 10_000; // 10 second window

    private final String validApiKey;
    private final SecurityMetrics metrics;

    // Simple in-memory rate limiter: apiKey → (windowStart, count)
    private final Map<String, long[]> rateLimitStore = new ConcurrentHashMap<>();

    public SecurityFilter(
            @Value("${bouncer.security.api-key:bouncer-dev-key-12345}") String validApiKey,
            SecurityMetrics metrics
    ) {
        this.validApiKey = validApiKey;
        this.metrics     = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip auth for actuator, dashboard, and static assets
        return path.startsWith("/actuator")
                || path.startsWith("/dashboard")
                || path.equals("/")
                || path.startsWith("/static")
                || path.endsWith(".html")
                || path.endsWith(".js")
                || path.endsWith(".css");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        String path   = request.getRequestURI();
        String apiKey = request.getHeader(API_KEY_HEADER);

        // ── 1. API Key check ──────────────────────────────────────────────
        if (apiKey == null || !apiKey.equals(validApiKey)) {
            metrics.recordAuthFailure();
            log.warn("auth_failure path={} ip={} key_present={}",
                    path, request.getRemoteAddr(), apiKey != null);
            sendError(response, 401, "Unauthorized",
                    "Missing or invalid " + API_KEY_HEADER + " header");
            return;
        }

        // ── 2. Rate limit check ───────────────────────────────────────────
        if (isRateLimited(apiKey)) {
            metrics.recordRateLimitRejection();
            log.warn("rate_limit_exceeded path={} key={}", path, maskKey(apiKey));
            sendError(response, 429, "Too Many Requests",
                    "Rate limit exceeded. Max " + RATE_LIMIT + " requests per " + (WINDOW_MS / 1000) + "s");
            return;
        }

        // ── 3. Track active requests ──────────────────────────────────────
        metrics.incrementActive();
        try {
            chain.doFilter(request, response);
        } finally {
            metrics.decrementActive();
        }
    }

    private boolean isRateLimited(String apiKey) {
        long now = Instant.now().toEpochMilli();
        long[] state = rateLimitStore.compute(apiKey, (k, existing) -> {
            if (existing == null || now - existing[0] > WINDOW_MS) {
                return new long[]{now, 1};
            }
            existing[1]++;
            return existing;
        });
        return state[1] > RATE_LIMIT;
    }

    private void sendError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
                status, error, message, Instant.now()
        ));
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****";
    }
}
