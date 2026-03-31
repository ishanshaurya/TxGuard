package com.bouncer.internal.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Charge-level metrics using Micrometer.
 *
 * <p>Metrics exposed at {@code GET /actuator/metrics/bouncer.*}:
 * <ul>
 *   <li>{@code bouncer.charges.initiated}    — counter, total charges accepted</li>
 *   <li>{@code bouncer.charges.settled}      — counter, total settled</li>
 *   <li>{@code bouncer.charges.failed}       — counter, total failed (with reason tag)</li>
 *   <li>{@code bouncer.charges.replayed}     — counter, idempotency replays</li>
 *   <li>{@code bouncer.charges.rejected}     — counter, 409 conflicts</li>
 *   <li>{@code bouncer.charge.settle.time}   — timer, consumer processing duration</li>
 * </ul>
 *
 * <p>All counters are tagged with {@code currency} so you can break down
 * throughput by INR vs USD etc. in a dashboard.
 */
@Component
public class ChargeMetrics {

    private final MeterRegistry registry;

    // ── Counters ──────────────────────────────────────────────────────────────
    private final Counter initiatedCounter;
    private final Counter settledCounter;
    private final Counter replayedCounter;
    private final Counter rejectedCounter;

    // ── Timer ─────────────────────────────────────────────────────────────────
    private final Timer settleTimer;

    public ChargeMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.initiatedCounter = Counter.builder("bouncer.charges.initiated")
                .description("Total charge requests accepted")
                .register(registry);

        this.settledCounter = Counter.builder("bouncer.charges.settled")
                .description("Total charges successfully settled")
                .register(registry);

        this.replayedCounter = Counter.builder("bouncer.charges.replayed")
                .description("Total idempotency key replays served from cache")
                .register(registry);

        this.rejectedCounter = Counter.builder("bouncer.charges.rejected")
                .description("Total requests rejected with 409 Conflict")
                .register(registry);

        this.settleTimer = Timer.builder("bouncer.charge.settle.time")
                .description("Time taken by the consumer to settle a charge")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry);
    }

    // ── Public recording methods ───────────────────────────────────────────────

    public void recordInitiated() {
        initiatedCounter.increment();
    }

    public void recordSettled() {
        settledCounter.increment();
    }

    public void recordFailed(String reason) {
        Counter.builder("bouncer.charges.failed")
                .description("Total charges that failed processing")
                .tag("reason", reason != null ? reason : "unknown")
                .register(registry)
                .increment();
    }

    public void recordReplayed() {
        replayedCounter.increment();
    }

    public void recordRejected(String reason) {
        Counter.builder("bouncer.charges.rejected")
                .description("Total requests rejected with 409 Conflict")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public Timer.Sample startSettleTimer() {
        return Timer.start(registry);
    }

    public void stopSettleTimer(Timer.Sample sample) {
        sample.stop(settleTimer);
    }
}
