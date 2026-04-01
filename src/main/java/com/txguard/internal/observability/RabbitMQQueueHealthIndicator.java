package com.txguard.internal.observability;

import com.txguard.internal.config.InfrastructureConfig;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Health indicator for RabbitMQ queue depth and consumer count.
 *
 * <p>Exposes at {@code GET /actuator/health/rabbitQueues}:
 * <pre>
 * {
 *   "status": "UP",
 *   "details": {
 *     "bouncer.charges.received": {
 *       "message_count": 3,
 *       "consumer_count": 2
 *     },
 *     "bouncer.charges.received.dlq": {
 *       "message_count": 0,
 *       "consumer_count": 1
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>Status is DOWN if:
 * <ul>
 *   <li>DLQ has any messages (charges are failing)</li>
 *   <li>Main queue has more than 1000 messages (consumer is falling behind)</li>
 *   <li>Main queue has no consumers (consumer crashed)</li>
 * </ul>
 */
@Component("rabbitQueues")
public class RabbitMQQueueHealthIndicator implements HealthIndicator {

    private static final int MAX_QUEUE_DEPTH   = 1000;
    private static final int MIN_CONSUMERS     = 1;

    private final RabbitAdmin rabbitAdmin;

    public RabbitMQQueueHealthIndicator(RabbitAdmin rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
    }

    @Override
    public Health health() {
        try {
            Properties mainProps = rabbitAdmin.getQueueProperties(
                    InfrastructureConfig.QUEUE_CHARGE_RECEIVED);
            Properties dlqProps  = rabbitAdmin.getQueueProperties(
                    InfrastructureConfig.QUEUE_CHARGE_DLQ);

            if (mainProps == null || dlqProps == null) {
                return Health.down()
                        .withDetail("error", "One or more queues not found")
                        .build();
            }

            int mainDepth     = intProp(mainProps, RabbitAdmin.QUEUE_MESSAGE_COUNT);
            int mainConsumers = intProp(mainProps, RabbitAdmin.QUEUE_CONSUMER_COUNT);
            int dlqDepth      = intProp(dlqProps,  RabbitAdmin.QUEUE_MESSAGE_COUNT);
            int dlqConsumers  = intProp(dlqProps,  RabbitAdmin.QUEUE_CONSUMER_COUNT);

            Health.Builder builder = Health.up();

            // ── Degraded / down conditions ────────────────────────────────
            if (dlqDepth > 0) {
                builder = Health.down()
                        .withDetail("alert", "DLQ has " + dlqDepth + " failed charge(s) — manual review needed");
            } else if (mainDepth > MAX_QUEUE_DEPTH) {
                builder = Health.down()
                        .withDetail("alert", "Queue depth " + mainDepth + " exceeds threshold " + MAX_QUEUE_DEPTH);
            } else if (mainConsumers < MIN_CONSUMERS) {
                builder = Health.down()
                        .withDetail("alert", "No active consumers on main queue");
            }

            return builder
                    .withDetail(InfrastructureConfig.QUEUE_CHARGE_RECEIVED, java.util.Map.of(
                            "message_count",  mainDepth,
                            "consumer_count", mainConsumers
                    ))
                    .withDetail(InfrastructureConfig.QUEUE_CHARGE_DLQ, java.util.Map.of(
                            "message_count",  dlqDepth,
                            "consumer_count", dlqConsumers
                    ))
                    .build();

        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }

    private int intProp(Properties props, Object key) {
        Object val = props.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Long l)    return l.intValue();
        return 0;
    }
}
