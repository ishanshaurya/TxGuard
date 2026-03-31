package com.bouncer.internal.model;

/**
 * State machine for a charge.
 *
 * <pre>
 *                ┌─────────┐
 *   [new charge] │ PENDING │
 *                └────┬────┘
 *                     │ processing starts
 *                ┌────▼──────┐
 *                │ PROCESSING│
 *                └────┬──────┘
 *          ┌──────────┴──────────┐
 *     ┌────▼────┐           ┌────▼───┐
 *     │ SETTLED │           │ FAILED │
 *     └─────────┘           └────────┘
 * </pre>
 *
 * <p>Transitions are enforced in {@code ChargeService}.
 * The DB column is a PostgreSQL ENUM — invalid transitions are rejected
 * at both the application and database level.
 *
 * <p>PENDING charges left behind by a crash are reviewed manually.
 */
public enum ChargeStatus {
    PENDING,
    PROCESSING,
    SETTLED,
    FAILED
}
