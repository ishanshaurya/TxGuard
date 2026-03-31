package com.bouncer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payment Bouncer — idempotency gateway for charge requests.
 *
 * <p>Package layout enforced from Phase 1:
 * <pre>
 *   com.bouncer
 *   └── internal/          ← nothing outside this package should import these
 *       ├── controller/    ← HTTP boundary layer
 *       ├── model/         ← request / response DTOs
 *       └── config/        ← Spring @Configuration classes
 * </pre>
 *
 * <p>The {@code internal} sub-package is a naming convention here; for hard
 * enforcement at build time, add a ArchUnit test in Phase 2.
 */
@SpringBootApplication
public class PaymentBouncerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentBouncerApplication.class, args);
    }
}
