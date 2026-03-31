package com.bouncer.internal.messaging;

import com.bouncer.internal.config.InfrastructureConfig;
import com.bouncer.internal.observability.ChargeMetrics;
import com.bouncer.internal.service.ChargeService;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Async consumer for charge events — now with metrics and structured logging.
 */
@Component
public class ChargeEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChargeEventConsumer.class);

    private final ChargeService chargeService;
    private final ChargeMetrics metrics;

    public ChargeEventConsumer(ChargeService chargeService, ChargeMetrics metrics) {
        this.chargeService = chargeService;
        this.metrics       = metrics;
    }

    @RabbitListener(queues = InfrastructureConfig.QUEUE_CHARGE_RECEIVED)
    public void consume(ChargeEvent event, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        log.info("charge_consumer_received charge_id={} delivery_tag={}",
                event.chargeId(), deliveryTag);

        Timer.Sample timer = metrics.startSettleTimer();

        try {
            chargeService.settle(event.chargeId());

            metrics.stopSettleTimer(timer);
            metrics.recordSettled();

            channel.basicAck(deliveryTag, false);

            log.info("charge_settled charge_id={}", event.chargeId());

        } catch (Exception ex) {
            log.error("charge_consumer_failed charge_id={} error={}",
                    event.chargeId(), ex.getMessage(), ex);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    @RabbitListener(queues = InfrastructureConfig.QUEUE_CHARGE_DLQ)
    public void consumeDlq(ChargeEvent event, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        log.warn("charge_dlq_received charge_id={} — marking FAILED", event.chargeId());

        try {
            chargeService.fail(event.chargeId(), "Exhausted all retry attempts");
            metrics.recordFailed("retry_exhausted");
            channel.basicAck(deliveryTag, false);
            log.warn("charge_failed_from_dlq charge_id={}", event.chargeId());
        } catch (Exception ex) {
            log.error("charge_dlq_handler_error charge_id={}", event.chargeId(), ex);
            channel.basicAck(deliveryTag, false);
        }
    }
}
