package com.txguard.internal.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metrics for security and active connection tracking.
 *
 * Exposes:
 *   bouncer.security.rate_limit_rejections  — 429 count
 *   bouncer.security.auth_failures          — 401 count
 *   bouncer.requests.active                 — in-flight requests right now
 */
@Component
public class SecurityMetrics {

    private final Counter rateLimitCounter;
    private final Counter authFailureCounter;
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    public SecurityMetrics(MeterRegistry registry) {
        this.rateLimitCounter = Counter.builder("bouncer.security.rate_limit_rejections")
                .description("Total requests rejected with 429 Too Many Requests")
                .register(registry);

        this.authFailureCounter = Counter.builder("bouncer.security.auth_failures")
                .description("Total requests rejected with 401 Unauthorized")
                .register(registry);

        Gauge.builder("bouncer.requests.active", activeRequests, AtomicInteger::get)
                .description("Currently in-flight requests being processed")
                .register(registry);
    }

    public void recordRateLimitRejection() { rateLimitCounter.increment(); }
    public void recordAuthFailure()         { authFailureCounter.increment(); }
    public void incrementActive()           { activeRequests.incrementAndGet(); }
    public void decrementActive()           { activeRequests.decrementAndGet(); }

    public double getRateLimitRejections()  { return rateLimitCounter.count(); }
    public double getAuthFailures()         { return authFailureCounter.count(); }
    public int getActiveRequests()          { return activeRequests.get(); }
}
