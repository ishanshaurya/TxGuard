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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Security filter handling three concerns:
 *
 * 1. API Key Authentication — constant-time comparison via MessageDigest.isEqual()
 *    to prevent timing attacks. Logs every failure without leaking the key.
 *
 * 2. Rate Limiting — sliding window per API key.
 *    Exceeding limit returns 429 Too Many Requests.
 *
 * 3. Active Request Tracking — increments/decrements live gauge.
 */
@Component
public class SecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final int    RATE_LIMIT     = 30;
    private static final long   WINDOW_MS      = 10_000L;

    private final byte[] validApiKeyBytes;
    private final SecurityMetrics metrics;
    private final Map<String, long[]> rateLimitStore = new ConcurrentHashMap<>();

    public SecurityFilter(
            @Value("${txguard.security.api-key}") String validApiKey,
            SecurityMetrics metrics
    ) {
        // Store as bytes once at startup — never log or expose the raw key
        this.validApiKeyBytes = validApiKey.getBytes(StandardCharsets.UTF_8);
        this.metrics          = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/dashboard")
                || path.equals("/")
                || path.startsWith("/static")
                || path.endsWith(".html")
                || path.endsWith(".css")
                || path.endsWith(".js");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        String path   = request.getRequestURI();
        String apiKey = request.getHeader(API_KEY_HEADER);

        // ── 1. Constant-time API key validation ───────────────────────────
        // MessageDigest.isEqual() compares every byte regardless of where
        // a mismatch occurs — eliminates timing oracle attacks.
        if (!isValidKey(apiKey)) {
            metrics.recordAuthFailure();
            log.warn("auth_failure path={} ip={} key_present={}",
                    path, request.getRemoteAddr(), apiKey != null);
            sendError(response, 401, "Unauthorized",
                    "Missing or invalid " + API_KEY_HEADER + " header");
            return;
        }

        // ── 2. Rate limiting ──────────────────────────────────────────────
        // Key for rate limiting is the SHA-256 of the API key so we never
        // store the raw secret in memory maps either.
        String rateLimitKey = hashKey(apiKey);
        if (isRateLimited(rateLimitKey)) {
            metrics.recordRateLimitRejection();
            log.warn("rate_limit_exceeded path={}", path);
            sendError(response, 429, "Too Many Requests",
                    "Rate limit exceeded. Max " + RATE_LIMIT +
                    " requests per " + (WINDOW_MS / 1000) + "s");
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

    /**
     * Constant-time comparison using MessageDigest.isEqual().
     * Hashing both sides first ensures equal-length byte arrays,
     * which is required for isEqual() to be timing-safe.
     */
    private boolean isValidKey(String providedKey) {
        if (providedKey == null) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] provided = digest.digest(
                    providedKey.getBytes(StandardCharsets.UTF_8));
            digest.reset();
            byte[] expected = digest.digest(validApiKeyBytes);
            return MessageDigest.isEqual(provided, expected);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * SHA-256 of the key — used as the rate-limit map key so the raw
     * secret is never stored in the ConcurrentHashMap.
     */
    private String hashKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString().substring(0, 16); // first 16 hex chars is enough
        } catch (Exception e) {
            return "unknown";
        }
    }

    private boolean isRateLimited(String key) {
        long now = Instant.now().toEpochMilli();
        long[] state = rateLimitStore.compute(key, (k, existing) -> {
            if (existing == null || now - existing[0] > WINDOW_MS) {
                return new long[]{now, 1};
            }
            existing[1]++;
            return existing;
        });
        return state[1] > RATE_LIMIT;
    }

    private void sendError(HttpServletResponse response, int status,
                           String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
                status, error, message, Instant.now()
        ));
    }
}
