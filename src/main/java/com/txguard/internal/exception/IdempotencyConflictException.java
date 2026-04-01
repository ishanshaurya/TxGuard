package com.txguard.internal.exception;

/**
 * Thrown when a request arrives with an idempotency key that is either:
 * <ul>
 *   <li>Already being processed (status = PROCESSING)</li>
 *   <li>Already completed but with a different request body fingerprint</li>
 * </ul>
 *
 * <p>Both cases map to HTTP 409 Conflict at the controller advice layer.
 */
public class IdempotencyConflictException extends RuntimeException {

    public enum Reason {
        /** The key was seen before but the request body is different. */
        BODY_MISMATCH,
        /** The key is currently being processed by another request. */
        ALREADY_PROCESSING
    }

    private final String idempotencyKey;
    private final Reason reason;

    public IdempotencyConflictException(String idempotencyKey, Reason reason) {
        super(buildMessage(idempotencyKey, reason));
        this.idempotencyKey = idempotencyKey;
        this.reason = reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Reason getReason() {
        return reason;
    }

    private static String buildMessage(String key, Reason reason) {
        return switch (reason) {
            case BODY_MISMATCH ->
                "Idempotency key '" + key + "' was already used with a different request body. " +
                "Use a new idempotency key for a different charge.";
            case ALREADY_PROCESSING ->
                "Idempotency key '" + key + "' is already being processed. " +
                "Retry after a short delay.";
        };
    }
}
