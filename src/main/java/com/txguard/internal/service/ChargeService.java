package com.txguard.internal.service;

import com.txguard.internal.entity.Charge;
import com.txguard.internal.entity.ChargeStatusHistory;
import com.txguard.internal.model.ChargeRequest;
import com.txguard.internal.model.ChargeStatus;
import com.txguard.internal.repository.ChargeRepository;
import com.txguard.internal.repository.ChargeStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Owns the charge lifecycle: creation, state transitions, and persistence.
 *
 * <h2>Phase 4 split</h2>
 * <pre>
 *  HTTP thread                    Consumer thread
 *  ──────────────────────         ──────────────────────────────
 *  initiate(request)              settle(chargeId)
 *    INSERT → PENDING               PENDING → PROCESSING → SETTLED
 *    return Charge                fail(chargeId, reason)
 *                                   PENDING → PROCESSING → FAILED
 * </pre>
 *
 * Every transition writes a ChargeStatusHistory row.
 * Each method runs in its own transaction.
 */
@Service
public class ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);

    private final ChargeRepository chargeRepository;
    private final ChargeStatusHistoryRepository historyRepository;

    public ChargeService(
            ChargeRepository chargeRepository,
            ChargeStatusHistoryRepository historyRepository
    ) {
        this.chargeRepository  = chargeRepository;
        this.historyRepository = historyRepository;
    }

    /**
     * Create a new charge in PENDING state and persist it.
     * Called from the HTTP thread — returns immediately after INSERT.
     *
     * @param request the validated charge request
     * @return the persisted Charge entity (status = PENDING)
     */
    @Transactional
    public Charge initiate(ChargeRequest request) {
        UUID chargeId = UUID.randomUUID();

        Charge charge = Charge.createPending(
                chargeId,
                request.idempotencyKey(),
                request.amount(),
                request.currency(),
                request.merchantReference(),
                request.paymentMethodToken()
        );
        chargeRepository.save(charge);
        writeHistory(chargeId, null, ChargeStatus.PENDING, "Charge initiated");

        log.info("Charge initiated: id={} idempotency_key={} amount={} {}",
                chargeId, request.idempotencyKey(), request.amount(), request.currency());

        return charge;
    }

    /**
     * Settle a charge — called by the RabbitMQ consumer after successful processing.
     * Transitions: PENDING → PROCESSING → SETTLED.
     *
     * @param chargeId the UUID of the charge to settle
     */
    @Transactional
    public void settle(String chargeId) {
        Charge charge = findById(chargeId);

        // PENDING → PROCESSING
        ChargeStatus prev = charge.getStatus();
        charge.transitionTo(ChargeStatus.PROCESSING);
        chargeRepository.save(charge);
        writeHistory(charge.getId(), prev, ChargeStatus.PROCESSING, "Consumer picked up charge");

        // PROCESSING → SETTLED
        prev = charge.getStatus();
        charge.transitionTo(ChargeStatus.SETTLED);
        chargeRepository.save(charge);
        writeHistory(charge.getId(), prev, ChargeStatus.SETTLED, "Charge settled successfully");

        log.info("Charge settled: id={}", chargeId);
    }

    /**
     * Fail a charge — called by the DLQ consumer after all retries are exhausted.
     * Transitions: PENDING → PROCESSING → FAILED.
     *
     * @param chargeId the UUID of the charge to fail
     * @param reason   human-readable failure reason stored on the charge
     */
    @Transactional
    public void fail(String chargeId, String reason) {
        Charge charge = findById(chargeId);

        // PENDING → PROCESSING (if not already)
        if (charge.getStatus() == ChargeStatus.PENDING) {
            ChargeStatus prev = charge.getStatus();
            charge.transitionTo(ChargeStatus.PROCESSING);
            chargeRepository.save(charge);
            writeHistory(charge.getId(), prev, ChargeStatus.PROCESSING, "DLQ: starting failure transition");
        }

        // PROCESSING → FAILED
        ChargeStatus prev = charge.getStatus();
        charge.transitionTo(ChargeStatus.FAILED, reason);
        chargeRepository.save(charge);
        writeHistory(charge.getId(), prev, ChargeStatus.FAILED, reason);

        log.warn("Charge failed: id={} reason={}", chargeId, reason);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Charge findById(String chargeId) {
        return chargeRepository.findById(UUID.fromString(chargeId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Charge not found: " + chargeId));
    }

    private void writeHistory(
            UUID chargeId,
            ChargeStatus fromStatus,
            ChargeStatus toStatus,
            String reason
    ) {
        historyRepository.save(
                ChargeStatusHistory.of(chargeId, fromStatus, toStatus, reason)
        );
    }
}
