package com.bouncer.internal.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published to RabbitMQ when a charge is created.
 *
 * <p>This is the contract between the HTTP layer (producer) and the
 * async consumer. All fields are immutable — never modify a published event.
 *
 * <p>Serialised as JSON on the wire. All field names use snake_case to match
 * the HTTP API contract and make rabbit-cli inspection readable.
 */
public record ChargeEvent(

        @JsonProperty("charge_id")
        String chargeId,

        @JsonProperty("idempotency_key")
        String idempotencyKey,

        @JsonProperty("amount")
        BigDecimal amount,

        @JsonProperty("currency")
        String currency,

        @JsonProperty("merchant_reference")
        String merchantReference,

        @JsonProperty("payment_method_token")
        String paymentMethodToken,

        @JsonProperty("published_at")
        Instant publishedAt

) {
    /**
     * Factory — build a ChargeEvent from a persisted charge ID and the original request fields.
     */
    public static ChargeEvent of(
            String chargeId,
            String idempotencyKey,
            BigDecimal amount,
            String currency,
            String merchantReference,
            String paymentMethodToken
    ) {
        return new ChargeEvent(
                chargeId,
                idempotencyKey,
                amount,
                currency,
                merchantReference,
                paymentMethodToken,
                Instant.now()
        );
    }
}
