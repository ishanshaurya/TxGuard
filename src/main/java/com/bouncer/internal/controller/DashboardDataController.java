package com.bouncer.internal.controller;

import com.bouncer.internal.model.ChargeStatus;
import com.bouncer.internal.observability.ChargeMetrics;
import com.bouncer.internal.repository.ChargeRepository;
import com.bouncer.internal.security.SecurityMetrics;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Properties;

/**
 * Serves aggregated real-time data to the dashboard frontend.
 * Not protected by API key — dashboard is internal tooling only.
 */
@RestController
@RequestMapping(path = "/dashboard", produces = MediaType.APPLICATION_JSON_VALUE)
public class DashboardDataController {

    private final ChargeRepository chargeRepository;
    private final ChargeMetrics chargeMetrics;
    private final SecurityMetrics securityMetrics;
    private final RabbitAdmin rabbitAdmin;

    public DashboardDataController(
            ChargeRepository chargeRepository,
            ChargeMetrics chargeMetrics,
            SecurityMetrics securityMetrics,
            RabbitAdmin rabbitAdmin
    ) {
        this.chargeRepository = chargeRepository;
        this.chargeMetrics    = chargeMetrics;
        this.securityMetrics  = securityMetrics;
        this.rabbitAdmin      = rabbitAdmin;
    }

    @GetMapping("/stats")
    public DashboardStats stats() {
        // Queue depths
        int mainDepth = 0, dlqDepth = 0;
        try {
            Properties mainProps = rabbitAdmin.getQueueProperties("bouncer.charges.received");
            Properties dlqProps  = rabbitAdmin.getQueueProperties("bouncer.charges.received.dlq");
            if (mainProps != null) mainDepth = toInt(mainProps.get("QUEUE_MESSAGE_COUNT"));
            if (dlqProps  != null) dlqDepth  = toInt(dlqProps.get("QUEUE_MESSAGE_COUNT"));
        } catch (Exception ignored) {}

        return new DashboardStats(
                chargeRepository.countByStatus(ChargeStatus.SETTLED),
                chargeRepository.countByStatus(ChargeStatus.FAILED),
                chargeRepository.countByStatus(ChargeStatus.PENDING),
                chargeRepository.countByStatus(ChargeStatus.PROCESSING),
                (long) securityMetrics.getRateLimitRejections(),
                (long) securityMetrics.getAuthFailures(),
                securityMetrics.getActiveRequests(),
                mainDepth,
                dlqDepth,
                Instant.now().toString()
        );
    }

    @GetMapping("/charges/recent")
    public Object recentCharges() {
        return chargeRepository.findAll(
                org.springframework.data.domain.PageRequest.of(
                        0, 10,
                        org.springframework.data.domain.Sort.by("createdAt").descending()
                )
        ).getContent().stream().map(c -> new ChargeRow(
                c.getId().toString(),
                c.getIdempotencyKey(),
                c.getAmount().toPlainString(),
                c.getCurrency(),
                c.getStatus().name(),
                c.getCreatedAt().toString(),
                c.getUpdatedAt().toString()
        )).toList();
    }

    private int toInt(Object val) {
        if (val instanceof Integer i) return i;
        if (val instanceof Long l)    return l.intValue();
        if (val instanceof String s)  { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
        return 0;
    }

    public record DashboardStats(
            @JsonProperty("charges_settled")     long chargesSettled,
            @JsonProperty("charges_failed")      long chargesFailed,
            @JsonProperty("charges_pending")     long chargesPending,
            @JsonProperty("charges_processing")  long chargesProcessing,
            @JsonProperty("rate_limit_rejections") long rateLimitRejections,
            @JsonProperty("auth_failures")       long authFailures,
            @JsonProperty("active_requests")     int activeRequests,
            @JsonProperty("queue_depth")         int queueDepth,
            @JsonProperty("dlq_depth")           int dlqDepth,
            @JsonProperty("timestamp")           String timestamp
    ) {}

    public record ChargeRow(
            @JsonProperty("id")              String id,
            @JsonProperty("idempotency_key") String idempotencyKey,
            @JsonProperty("amount")          String amount,
            @JsonProperty("currency")        String currency,
            @JsonProperty("status")          String status,
            @JsonProperty("created_at")      String createdAt,
            @JsonProperty("updated_at")      String updatedAt
    ) {}
}
