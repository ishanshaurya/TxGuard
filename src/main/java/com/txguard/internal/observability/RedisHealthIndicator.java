package com.txguard.internal.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

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

            var keys = redisTemplate.keys("idempotency:charge:*");
            long keyCount = keys != null ? keys.size() : 0;

            Health.Builder builder = latencyMs > MAX_LATENCY_MS
                    ? Health.down().withDetail("alert", "Redis latency " + latencyMs + "ms exceeds threshold")
                    : Health.up();

            return builder
                    .withDetail("ping_latency_ms", latencyMs)
                    .withDetail("idempotency_keys_cached", keyCount)
                    .build();

        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
