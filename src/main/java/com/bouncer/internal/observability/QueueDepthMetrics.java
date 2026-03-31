package com.bouncer.internal.observability;

import com.bouncer.internal.config.InfrastructureConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Registers Micrometer gauges for RabbitMQ queue depths.
 *
 * <p>Exposes two metrics visible at {@code GET /actuator/metrics}:
 * <ul>
 *   <li>{@code rabbitmq.queue.messages} (tag: queue=bouncer.charges.received)</li>
 *   <li>{@code rabbitmq.queue.messages} (tag: queue=bouncer.charges.received.dlq)</li>
 * </ul>
 *
 * <p>These are pull-based gauges — the value is re-fetched from RabbitMQ
 * every time a metrics scrape happens (e.g. every 15s from Prometheus).
 *
 * <p>Example Prometheus query to alert on DLQ backup:
 * <pre>
 *   rabbitmq_queue_messages{queue="bouncer.charges.received.dlq"} > 0
 * </pre>
 */
@Component
public class QueueDepthMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueDepthMetrics.class);

    private final RabbitAdmin   rabbitAdmin;
    private final MeterRegistry meterRegistry;

    public QueueDepthMetrics(RabbitAdmin rabbitAdmin, MeterRegistry meterRegistry) {
        this.rabbitAdmin   = rabbitAdmin;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerGauges() {
        registerQueueGauge(InfrastructureConfig.QUEUE_CHARGE_RECEIVED);
        registerQueueGauge(InfrastructureConfig.QUEUE_CHARGE_DLQ);
        log.info("Queue depth gauges registered for {} and {}",
                InfrastructureConfig.QUEUE_CHARGE_RECEIVED,
                InfrastructureConfig.QUEUE_CHARGE_DLQ);
    }

    private void registerQueueGauge(String queueName) {
        Gauge.builder("rabbitmq.queue.messages", () -> fetchDepth(queueName))
                .description("Number of messages waiting in the queue")
                .tag("queue", queueName)
                .register(meterRegistry);
    }

    private double fetchDepth(String queueName) {
        try {
            Properties props = rabbitAdmin.getQueueProperties(queueName);
            if (props == null) return 0.0;
            Object count = props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            if (count instanceof Number n) return n.doubleValue();
            return 0.0;
        } catch (Exception ex) {
            log.warn("Failed to fetch queue depth for {}: {}", queueName, ex.getMessage());
            return -1.0; // -1 signals metric collection failure
        }
    }
}
