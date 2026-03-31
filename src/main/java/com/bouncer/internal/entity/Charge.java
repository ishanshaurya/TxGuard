package com.bouncer.internal.entity;

import com.bouncer.internal.model.ChargeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of a charge attempt.
 *
 * One row per unique idempotency_key. Status transitions are
 * managed exclusively by ChargeService.
 */
@Entity
@Table(name = "charges")
public class Charge {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "merchant_reference", nullable = false, length = 255)
    private String merchantReference;

    @Column(name = "payment_method_token", nullable = false, length = 255)
    private String paymentMethodToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ChargeStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Charge() {}

    /** Factory — create a new charge in PENDING state. */
    public static Charge createPending(
            UUID id,
            String idempotencyKey,
            BigDecimal amount,
            String currency,
            String merchantReference,
            String paymentMethodToken
    ) {
        Charge c = new Charge();
        c.id                  = id;
        c.idempotencyKey      = idempotencyKey;
        c.amount              = amount;
        c.currency            = currency;
        c.merchantReference   = merchantReference;
        c.paymentMethodToken  = paymentMethodToken;
        c.status              = ChargeStatus.PENDING;
        c.createdAt           = Instant.now();
        c.updatedAt           = c.createdAt;
        return c;
    }

    // ── State transitions ─────────────────────────────────────────────────────

    public void transitionTo(ChargeStatus newStatus) {
        transitionTo(newStatus, null);
    }

    public void transitionTo(ChargeStatus newStatus, String reason) {
        validateTransition(this.status, newStatus);
        this.status        = newStatus;
        this.failureReason = reason;
        this.updatedAt     = Instant.now();
    }

    private static void validateTransition(ChargeStatus from, ChargeStatus to) {
        boolean valid = switch (from) {
            case PENDING    -> to == ChargeStatus.PROCESSING;
            case PROCESSING -> to == ChargeStatus.SETTLED || to == ChargeStatus.FAILED;
            case SETTLED, FAILED -> false;
        };
        if (!valid) {
            throw new IllegalStateException(
                "Invalid charge status transition: " + from + " -> " + to
            );
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public UUID getId()                   { return id; }
    public String getIdempotencyKey()     { return idempotencyKey; }
    public BigDecimal getAmount()         { return amount; }
    public String getCurrency()           { return currency; }
    public String getMerchantReference()  { return merchantReference; }
    public String getPaymentMethodToken() { return paymentMethodToken; }
    public ChargeStatus getStatus()       { return status; }
    public String getFailureReason()      { return failureReason; }
    public Instant getCreatedAt()         { return createdAt; }
    public Instant getUpdatedAt()         { return updatedAt; }
}
