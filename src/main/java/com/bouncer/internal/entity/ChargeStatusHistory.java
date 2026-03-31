package com.bouncer.internal.entity;

import com.bouncer.internal.model.ChargeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit log of every status transition for a charge.
 *
 * <p>One row is written for every call to {@code Charge.transitionTo()}.
 * The initial PENDING entry has {@code fromStatus = null}.
 *
 * <p><b>Never update or delete rows from this table.</b>
 */
@Entity
@Table(name = "charge_status_history")
public class ChargeStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "charge_id", nullable = false, updatable = false)
    private UUID chargeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private ChargeStatus fromStatus;  // null for the initial PENDING row

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private ChargeStatus toStatus;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChargeStatusHistory() {}

    public static ChargeStatusHistory of(
            UUID chargeId,
            ChargeStatus fromStatus,
            ChargeStatus toStatus,
            String reason
    ) {
        ChargeStatusHistory h = new ChargeStatusHistory();
        h.chargeId   = chargeId;
        h.fromStatus = fromStatus;
        h.toStatus   = toStatus;
        h.reason     = reason;
        h.createdAt  = Instant.now();
        return h;
    }

    public Long getId()                { return id; }
    public UUID getChargeId()          { return chargeId; }
    public ChargeStatus getFromStatus(){ return fromStatus; }
    public ChargeStatus getToStatus()  { return toStatus; }
    public String getReason()          { return reason; }
    public Instant getCreatedAt()      { return createdAt; }
}
