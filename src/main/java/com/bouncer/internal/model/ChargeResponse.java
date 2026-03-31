package com.bouncer.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Response envelope for {@code POST /api/v1/charge}.
 *
 * <p>Phase 1: all fields are hardcoded stubs. Real values wired in Phase 2+.
 *
 * <p>Using a record keeps this immutable and serialiser-friendly with no extra config.
 */
public record ChargeResponse(

        /**
         * Mirrors the caller's idempotency key so they can correlate the response.
         */
        @JsonProperty("idempotency_key")
        String idempotencyKey,

        /**
         * Platform-generated charge ID. Phase 1: always {@code "STUB-0000"}.
         */
        @JsonProperty("charge_id")
        String chargeId,

        /**
         * Charge status. Phase 1: always {@code "ACCEPTED"}.
         * Full state machine (PENDING → PROCESSING → SETTLED | FAILED) in Phase 3.
         */
        @JsonProperty("status")
        String status,

        /**
         * Human-readable message for debugging.
         */
        @JsonProperty("message")
        String message,

        /**
         * Server-side timestamp of response creation (ISO-8601).
         */
        @JsonProperty("processed_at")
        Instant processedAt

) {
    /**
     * Factory: build a Phase-1 stub response from a {@link ChargeRequest}.
     */
    public static ChargeResponse stubFor(ChargeRequest request) {
        return new ChargeResponse(
                request.idempotencyKey(),
                "STUB-0000",
                "ACCEPTED",
                "Phase 1 skeleton — real processing coming in Phase 2",
                Instant.now()
        );
    }
}
