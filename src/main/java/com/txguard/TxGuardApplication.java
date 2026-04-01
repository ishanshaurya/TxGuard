package com.txguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payment Bouncer — idempotency gateway for charge requests.
 *
 * <p>Package layout enforced from Phase 1:
 * <pre>
 *   com.txguard
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
public class TxGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(TxGuardApplication.class, args);
    }
}
