package com.bouncer.internal.observability;

import com.bouncer.internal.model.ChargeStatus;
import com.bouncer.internal.repository.ChargeRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Health indicator for PostgreSQL — connection pool stats and charge counts.
 *
 * <p>Exposes at {@code GET /actuator/health/databaseDetail}:
 * <pre>
 * {
 *   "status": "UP",
 *   "details": {
 *     "pool_active_connections": 2,
 *     "pool_idle_connections": 8,
 *     "pool_total_connections": 10,
 *     "pool_threads_awaiting": 0,
 *     "charges_pending": 0,
 *     "charges_processing": 0,
 *     "charges_settled": 142,
 *     "charges_failed": 1
 *   }
 * }
 * </pre>
 *
 * <p>Status is DOWN if:
 * <ul>
 *   <li>Threads are waiting for a connection (pool exhausted)</li>
 *   <li>Any charge has been stuck in PROCESSING for too long (Phase 6)</li>
 * </ul>
 */
@Component("databaseDetail")
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;
    private final ChargeRepository chargeRepository;

    public DatabaseHealthIndicator(DataSource dataSource, ChargeRepository chargeRepository) {
        this.dataSource        = dataSource;
        this.chargeRepository  = chargeRepository;
    }

    @Override
    public Health health() {
        try {
            Health.Builder builder = Health.up();

            // ── Connection pool stats (HikariCP) ──────────────────────────
            if (dataSource instanceof HikariDataSource hikari) {
                HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
                if (pool != null) {
                    int awaiting = pool.getThreadsAwaitingConnection();
                    if (awaiting > 0) {
                        builder = Health.down()
                                .withDetail("alert", awaiting + " thread(s) waiting for DB connection");
                    }
                    builder
                            .withDetail("pool_active_connections",  pool.getActiveConnections())
                            .withDetail("pool_idle_connections",    pool.getIdleConnections())
                            .withDetail("pool_total_connections",   pool.getTotalConnections())
                            .withDetail("pool_threads_awaiting",    awaiting);
                }
            }

            // ── Charge status counts ───────────────────────────────────────
            builder
                    .withDetail("charges_pending",    chargeRepository.countByStatus(ChargeStatus.PENDING))
                    .withDetail("charges_processing",  chargeRepository.countByStatus(ChargeStatus.PROCESSING))
                    .withDetail("charges_settled",     chargeRepository.countByStatus(ChargeStatus.SETTLED))
                    .withDetail("charges_failed",      chargeRepository.countByStatus(ChargeStatus.FAILED));

            return builder.build();

        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
