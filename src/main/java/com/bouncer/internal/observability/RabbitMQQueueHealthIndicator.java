package com.bouncer.internal.observability;

import com.bouncer.internal.config.InfrastructureConfig;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Component("rabbitQueues")
public class RabbitMQQueueHealthIndicator implements HealthIndicator {

    private final RabbitAdmin rabbitAdmin;

    public RabbitMQQueueHealthIndicator(RabbitAdmin rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
    }

    @Override
    public Health health() {
        try {
            Properties main = rabbitAdmin.getQueueProperties(InfrastructureConfig.QUEUE_CHARGE_RECEIVED);
            Properties dlq  = rabbitAdmin.getQueueProperties(InfrastructureConfig.QUEUE_CHARGE_DLQ);
            if (main == null || dlq == null) {
                return Health.down().withDetail("error", "Queues not found").build();
            }
            int mainDepth     = toInt(main.get("QUEUE_MESSAGE_COUNT"));
            int mainConsumers = toInt(main.get("QUEUE_CONSUMER_COUNT"));
            int dlqDepth      = toInt(dlq.get("QUEUE_MESSAGE_COUNT"));
            int dlqConsumers  = toInt(dlq.get("QUEUE_CONSUMER_COUNT"));

            Health.Builder b = dlqDepth > 0
                ? Health.down().withDetail("alert", "DLQ has " + dlqDepth + " failed charge(s)")
                : Health.up();

            return b
                .withDetail("main_queue_messages",  Integer.toString(mainDepth))
                .withDetail("main_queue_consumers", Integer.toString(mainConsumers))
                .withDetail("dlq_messages",         Integer.toString(dlqDepth))
                .withDetail("dlq_consumers",        Integer.toString(dlqConsumers))
                .build();
        } catch (Exception ex) {
            return Health.down().withDetail("error", ex.getMessage()).build();
        }
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }
}
