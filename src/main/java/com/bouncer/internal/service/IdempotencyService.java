package com.bouncer.internal.service;

import com.bouncer.internal.exception.IdempotencyConflictException;
import com.bouncer.internal.exception.IdempotencyConflictException.Reason;
import com.bouncer.internal.model.ChargeRequest;
import com.bouncer.internal.model.ChargeResponse;
import com.bouncer.internal.model.IdempotencyRecord;
import com.bouncer.internal.model.IdempotencyStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Core idempotency logic — the "bouncer" that decides whether a request
 * gets processed or turned away.
 *
 * <h2>Decision table</h2>
 * <pre>
 * Key in Redis?  |  Body match?  |  Status       |  Action
 * ───────────────┼───────────────┼───────────────┼──────────────────────────
 * No             |  —            |  —            |  Proceed (write PROCESSING)
 * Yes            |  Yes          |  PROCESSING   |  409 ALREADY_PROCESSING
 * Yes            |  Yes          |  COMPLETED    |  Return cached response
 * Yes            |  No           |  any          |  409 BODY_MISMATCH
 * </pre>
 *
 * <h2>Redis key format</h2>
 * <pre>idempotency:charge:{idempotency_key}</pre>
 *
 * <h2>Atomicity</h2>
 * <p>We use {@code SET NX} (set-if-not-exists) to atomically claim a key.
 * Only one request wins the race; all others see the key and are bounced.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private static final String KEY_PREFIX = "idempotency:charge:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public IdempotencyService(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            @Value("${bouncer.idempotency.ttl-hours:24}") long ttlHours
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper  = objectMapper;
        this.ttl           = Duration.ofHours(ttlHours);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Attempt to claim an idempotency key before processing a charge.
     *
     * <p>Outcomes:
     * <ul>
     *   <li>Key is new → atomically writes PROCESSING record, returns empty Optional.
     *       Caller must process the charge and then call {@link #complete}.</li>
     *   <li>Key exists + same body + COMPLETED → returns cached response in Optional.</li>
     *   <li>Key exists + same body + PROCESSING → throws {@link IdempotencyConflictException}.</li>
     *   <li>Key exists + different body → throws {@link IdempotencyConflictException}.</li>
     * </ul>
     *
     * @param request the incoming charge request
     * @return empty if the request should be processed; cached response if it's a safe replay
     * @throws IdempotencyConflictException if the request must be rejected
     */
    public Optional<ChargeResponse> checkAndClaim(ChargeRequest request) {
        String redisKey   = toRedisKey(request.idempotencyKey());
        String fingerprint = fingerprint(request);

        // ── Try to atomically claim the key (SET NX EX) ──────────────────
        IdempotencyRecord processingRecord = IdempotencyRecord.processing(fingerprint);
        String serialised = serialise(processingRecord);

        Boolean claimed = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, serialised, ttl);

        if (Boolean.TRUE.equals(claimed)) {
            // We won the race — key is ours to process
            log.debug("Idempotency key claimed: key={} fingerprint={}", request.idempotencyKey(), fingerprint);
            return Optional.empty();
        }

        // ── Key already exists — read what's there ────────────────────────
        String existing = redisTemplate.opsForValue().get(redisKey);
        if (existing == null) {
            // Extremely rare: key expired between setIfAbsent and get — treat as new
            log.warn("Race condition on key expiry for key={}, treating as new", request.idempotencyKey());
            redisTemplate.opsForValue().set(redisKey, serialised, ttl);
            return Optional.empty();
        }

        IdempotencyRecord record = deserialise(existing);

        // ── Body mismatch check (applies regardless of status) ────────────
        if (!fingerprint.equals(record.bodyFingerprint())) {
            log.warn("Body mismatch for idempotency key={}", request.idempotencyKey());
            throw new IdempotencyConflictException(request.idempotencyKey(), Reason.BODY_MISMATCH);
        }

        // ── Same body — check status ───────────────────────────────────────
        if (record.status() == IdempotencyStatus.PROCESSING) {
            log.warn("Key still processing: key={}", request.idempotencyKey());
            throw new IdempotencyConflictException(request.idempotencyKey(), Reason.ALREADY_PROCESSING);
        }

        // ── COMPLETED — safe replay ────────────────────────────────────────
        log.info("Replaying cached response for key={}", request.idempotencyKey());
        return Optional.of(record.response());
    }

    /**
     * Mark an idempotency key as COMPLETED and store the final response.
     *
     * <p>Must be called after every successful charge processing.
     * Overwrites the PROCESSING record with a COMPLETED record.
     * TTL is reset to the full window so callers have a full 24h from completion.
     *
     * @param request  the original charge request
     * @param response the response to cache for future replays
     */
    public void complete(ChargeRequest request, ChargeResponse response) {
        String redisKey    = toRedisKey(request.idempotencyKey());
        String fingerprint = fingerprint(request);

        IdempotencyRecord completed = IdempotencyRecord.completed(fingerprint, response);
        redisTemplate.opsForValue().set(redisKey, serialise(completed), ttl);

        log.debug("Idempotency key completed: key={}", request.idempotencyKey());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String toRedisKey(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey;
    }

    /**
     * SHA-256 fingerprint of the canonical request body fields.
     *
     * <p>We hash the business fields (not the idempotency key itself) so that
     * replaying the same key with a different amount/currency is detectable.
     */
    private String fingerprint(ChargeRequest request) {
        String canonical = String.join("|",
                request.amount().toPlainString(),
                request.currency(),
                request.merchantReference(),
                request.paymentMethodToken()
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec — this never happens
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String serialise(IdempotencyRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise IdempotencyRecord", e);
        }
    }

    private IdempotencyRecord deserialise(String json) {
        try {
            return objectMapper.readValue(json, IdempotencyRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialise IdempotencyRecord: " + json, e);
        }
    }
}
