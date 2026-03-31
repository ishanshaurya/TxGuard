package com.bouncer.internal.model;

/**
 * All possible states an idempotency key can be in when looked up from Redis.
 *
 * <pre>
 *  (key absent)
 *       │
 *       ▼
 *   PROCESSING  ──── first request in flight, lock held
 *       │
 *       ▼
 *   COMPLETED   ──── response cached, replay returns this
 * </pre>
 *
 * CONFLICT is not stored — it is computed at lookup time when the
 * caller sends a different body fingerprint for an existing key.
 */
public enum IdempotencyStatus {

    /** Key exists in Redis and the original request is still being processed. */
    PROCESSING,

    /** Key exists and a completed response is cached — safe to replay. */
    COMPLETED
}
