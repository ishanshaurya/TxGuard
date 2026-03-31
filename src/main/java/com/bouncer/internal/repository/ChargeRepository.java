package com.bouncer.internal.repository;

import com.bouncer.internal.entity.Charge;
import com.bouncer.internal.model.ChargeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Charge} entities.
 *
 * <p>All writes go through {@code ChargeService} — never call
 * {@code save()} directly from the controller layer.
 */
@Repository
public interface ChargeRepository extends JpaRepository<Charge, UUID> {

    /**
     * Look up a charge by its idempotency key.
     * Used during reconciliation and in tests.
     */
    Optional<Charge> findByIdempotencyKey(String idempotencyKey);

    /**
     * Find all charges in a given status.
     * Used by ops tooling to surface PENDING charges left by crashes.
     */
    List<Charge> findByStatusOrderByCreatedAtDesc(ChargeStatus status);

    /**
     * Count charges by status — used for health metrics in Phase 5.
     */
    @Query("SELECT COUNT(c) FROM Charge c WHERE c.status = :status")
    long countByStatus(ChargeStatus status);
}
