package com.bouncer.internal.messaging;

import com.bouncer.internal.config.InfrastructureConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link ChargeEvent} messages to the RabbitMQ charges exchange.
 *
 * <p>Uses {@link RabbitTemplate} which is already configured with the
 * {@link com.fasterxml.jackson.databind.ObjectMapper} JSON converter
 * declared in {@link InfrastructureConfig}.
 *
 * <p>Publishing is fire-and-forget from the HTTP thread's perspective.
 * The consumer processes asynchronously and updates the DB independently.
 */
@Component
public class ChargeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChargeEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public ChargeEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Publish a charge event to the charges exchange.
     *
     * @param event the charge event to publish
     */
    public void publish(ChargeEvent event) {
        log.debug("Publishing charge event: charge_id={} idempotency_key={}",
                event.chargeId(), event.idempotencyKey());

        rabbitTemplate.convertAndSend(
                InfrastructureConfig.EXCHANGE_CHARGES,
                InfrastructureConfig.ROUTING_CHARGE_RECEIVED,
                event
        );

        log.info("Charge event published: charge_id={}", event.chargeId());
    }
}
