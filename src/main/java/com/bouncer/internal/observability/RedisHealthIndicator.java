package com.bouncer.internal.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Health indicator for Redis — measures round-trip latency with PING.
 *
 * <p>Exposes at {@code GET /actuator/health/redisDetail}:
 * <pre>
 * {
 *   "status": "UP",
 *   "details": {
 *     "ping_latency_ms": 1,
 *     "idempotency_keys_sample": 42
 *   }
 * }
 * </pre>
 *
 * <p>Status is DOWN if:
 * <ul>
 *   <li>Redis is unreachable</li>
 *   <li>PING latency exceeds 500ms (severely degraded)</li>
 * </ul>
 */
@Component("redisDetail")
public class RedisHealthIndicator implements HealthIndicator {

    private static final long MAX_LATENCY_MS = 500;

    private final RedisTemplate<String, String> redisTemplate;

    public RedisHealthIndicator(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Health health() {
        try {
            long start = System.currentTimeMillis();
            redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            long latencyMs = System.currentTimeMillis() - start;

            // Count idempotency keys as a useful operational metric
            Long keyCount = redisTemplate.keys("idempotency:charge:*").size();

            Health.Builder builder = latencyMs > MAX_LATENCY_MS
                    ? Health.down().withDetail("alert", "Redis latency " + latencyMs + "ms exceeds threshold")
                    : Health.up();

            return builder
                    .withDetail("ping_latency_ms", latencyMs)
                    .withDetail("idempotency_keys_cached", keyCount != null ? keyCount : 0)
                    .build();

        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
