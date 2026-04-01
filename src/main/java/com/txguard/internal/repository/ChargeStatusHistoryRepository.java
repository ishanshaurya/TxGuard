package com.txguard.internal.repository;

import com.txguard.internal.entity.ChargeStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Data access for the append-only {@link ChargeStatusHistory} audit log.
 */
@Repository
public interface ChargeStatusHistoryRepository extends JpaRepository<ChargeStatusHistory, Long> {

    /**
     * Returns the full history for a charge, oldest first.
     */
    List<ChargeStatusHistory> findByChargeIdOrderByCreatedAtAsc(UUID chargeId);
}
