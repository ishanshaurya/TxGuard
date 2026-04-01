package com.txguard.internal.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Inbound request model for {@code POST /api/v1/charge}.
 *
 * <p>This is the canonical wire contract carried forward through every phase.
 * All fields validated at the HTTP boundary; no business logic lives here.
 *
 * <p><b>Idempotency key contract (Phase 2+):</b> callers MUST supply a unique
 * {@code idempotencyKey} per logical charge attempt. Replaying the same key
 * with identical body will return the original response without double-charging.
 */
public record ChargeRequest(

        /**
         * Caller-generated idempotency key.
         * Format: UUID v4 string, e.g. {@code "550e8400-e29b-41d4-a716-446655440000"}.
         * Max 64 chars to fit comfortably in a Redis key.
         */
        @JsonProperty("idempotency_key")
        @NotBlank(message = "idempotency_key is required")
        @Size(min = 1, max = 64, message = "idempotency_key must be between 1 and 64 characters")
        @Pattern(
                regexp = "^[a-zA-Z0-9\\-_]+$",
                message = "idempotency_key may only contain alphanumerics, hyphens, and underscores"
        )
        String idempotencyKey,

        /**
         * Amount to charge in the smallest currency unit (e.g. paise for INR, cents for USD).
         * Must be > 0; no fractional sub-units.
         */
        @JsonProperty("amount")
        @NotNull(message = "amount is required")
        @DecimalMin(value = "1", message = "amount must be at least 1")
        BigDecimal amount,

        /**
         * ISO 4217 currency code, e.g. "INR", "USD".
         * Exactly 3 uppercase letters.
         */
        @JsonProperty("currency")
        @NotBlank(message = "currency is required")
        @Pattern(
                regexp = "^[A-Z]{3}$",
                message = "currency must be a 3-letter ISO 4217 code (e.g. INR, USD)"
        )
        String currency,

        /**
         * Opaque merchant-side reference for this charge.
         * Stored as-is; used for reconciliation in later phases.
         */
        @JsonProperty("merchant_reference")
        @NotBlank(message = "merchant_reference is required")
        @Size(max = 255, message = "merchant_reference must not exceed 255 characters")
        String merchantReference,

        /**
         * Payment method token — represents the customer's payment instrument.
         * Real tokenisation wired in Phase 3+; anything non-blank accepted here.
         */
        @JsonProperty("payment_method_token")
        @NotBlank(message = "payment_method_token is required")
        String paymentMethodToken

) {}
