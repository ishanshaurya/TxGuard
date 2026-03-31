package com.bouncer.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * The value stored in Redis for each idempotency key.
 *
 * <p>Serialised as JSON so it is human-readable in redis-cli:
 * <pre>
 * GET idempotency:charge:smoke-001
 * {
 *   "status": "COMPLETED",
 *   "body_fingerprint": "a3f5...",
 *   "response": { ... ChargeResponse fields ... },
 *   "created_at": "2024-01-01T00:00:00Z"
 * }
 * </pre>
 *
 * <p>{@code response} is {@code null} while status is {@code PROCESSING}.
 */
public record IdempotencyRecord(

        /**
         * Current state of this key.
         */
        @JsonProperty("status")
        IdempotencyStatus status,

        /**
         * SHA-256 hex digest of the canonical request body.
         * Used to detect body mismatches on replayed keys.
         */
        @JsonProperty("body_fingerprint")
        String bodyFingerprint,

        /**
         * The cached response to replay. Null while PROCESSING.
         */
        @JsonProperty("response")
        ChargeResponse response,

        /**
         * When this record was first written.
         */
        @JsonProperty("created_at")
        Instant createdAt

) {
    /**
     * Factory: create a PROCESSING record (no response yet).
     */
    public static IdempotencyRecord processing(String bodyFingerprint) {
        return new IdempotencyRecord(
                IdempotencyStatus.PROCESSING,
                bodyFingerprint,
                null,
                Instant.now()
        );
    }

    /**
     * Factory: create a COMPLETED record with the final response.
     */
    public static IdempotencyRecord completed(String bodyFingerprint, ChargeResponse response) {
        return new IdempotencyRecord(
                IdempotencyStatus.COMPLETED,
                bodyFingerprint,
                response,
                Instant.now()
        );
    }
}
