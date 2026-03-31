package com.bouncer.internal.controller;

import com.bouncer.internal.entity.Charge;
import com.bouncer.internal.messaging.ChargeEvent;
import com.bouncer.internal.messaging.ChargeEventPublisher;
import com.bouncer.internal.model.ChargeRequest;
import com.bouncer.internal.model.ChargeResponse;
import com.bouncer.internal.observability.ChargeMetrics;
import com.bouncer.internal.service.ChargeService;
import com.bouncer.internal.service.IdempotencyService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChargeController {

    private static final Logger log = LoggerFactory.getLogger(ChargeController.class);

    private final IdempotencyService idempotencyService;
    private final ChargeService chargeService;
    private final ChargeEventPublisher eventPublisher;
    private final ChargeMetrics metrics;

    public ChargeController(
            IdempotencyService idempotencyService,
            ChargeService chargeService,
            ChargeEventPublisher eventPublisher,
            ChargeMetrics metrics
    ) {
        this.idempotencyService = idempotencyService;
        this.chargeService      = chargeService;
        this.eventPublisher     = eventPublisher;
        this.metrics            = metrics;
    }

    @PostMapping(path = "/charge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChargeResponse> charge(@Valid @RequestBody ChargeRequest request) {
        log.info("charge_request idempotency_key={} amount={} currency={}",
                request.idempotencyKey(), request.amount(), request.currency());

        // ── Idempotency gate ───────────────────────────────────────────────
        Optional<ChargeResponse> cached = idempotencyService.checkAndClaim(request);
        if (cached.isPresent()) {
            metrics.recordReplayed();
            log.info("charge_replayed idempotency_key={}", request.idempotencyKey());
            return ResponseEntity.ok(cached.get());
        }

        // ── Persist PENDING ────────────────────────────────────────────────
        Charge charge = chargeService.initiate(request);
        metrics.recordInitiated();

        // ── Publish to RabbitMQ ────────────────────────────────────────────
        ChargeEvent event = ChargeEvent.of(
                charge.getId().toString(),
                charge.getIdempotencyKey(),
                charge.getAmount(),
                charge.getCurrency(),
                charge.getMerchantReference(),
                charge.getPaymentMethodToken()
        );
        eventPublisher.publish(event);

        // ── Build and cache response ───────────────────────────────────────
        ChargeResponse response = new ChargeResponse(
                charge.getIdempotencyKey(),
                charge.getId().toString(),
                charge.getStatus().name(),
                "Charge accepted — processing asynchronously",
                Instant.now()
        );
        idempotencyService.complete(request, response);

        log.info("charge_accepted charge_id={} idempotency_key={}",
                charge.getId(), request.idempotencyKey());

        return ResponseEntity.ok(response);
    }
}
