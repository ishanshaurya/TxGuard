# Payment Bouncer — Phase 1: Skeleton

> **Goal**: Prove the wire works. 100 `POST /api/v1/charge` requests in, 100 `200 OK` out. Nothing crashes.

---

## What's in this phase

| Thing | Purpose |
|---|---|
| `docker-compose.yml` | PostgreSQL 16, Redis 7, RabbitMQ 3.13, Spring Boot app |
| `pom.xml` | All Spring Boot 3.3 dependencies locked in |
| `ChargeRequest.java` | Wire contract with full Bean Validation |
| `ChargeController.java` | `POST /api/v1/charge` shell — hardcoded `200 OK` |
| `ChargeResponse.java` | Stub response envelope |
| `GlobalExceptionHandler.java` | Clean `400`/`500` JSON envelopes from day one |
| `InfrastructureConfig.java` | Redis template + RabbitMQ topology declared |
| `stress-test.sh` | Fires 100 requests, fails if any aren't `200` |

---

## Prerequisites

- Docker Desktop ≥ 24 with Compose v2
- Java 21 (only needed if running outside Docker)
- `curl` (for the stress test)

---

## Quick start — full Docker stack

```bash
# 1. Build and bring everything up
docker compose up --build

# 2. Wait for the health check log line:
#    bouncer-app  | Started PaymentBouncerApplication in X.XXXs

# 3. Smoke test — single request
curl -s -X POST http://localhost:8080/api/v1/charge \
  -H "Content-Type: application/json" \
  -d '{
    "idempotency_key":      "smoke-001",
    "amount":               5000,
    "currency":             "INR",
    "merchant_reference":   "order-smoke-001",
    "payment_method_token": "tok_visa_4242"
  }' | jq .

# Expected response:
# {
#   "idempotency_key": "smoke-001",
#   "charge_id": "STUB-0000",
#   "status": "ACCEPTED",
#   "message": "Phase 1 skeleton — real processing coming in Phase 2",
#   "processed_at": "2024-..."
# }
```

---

## Quick start — local JVM (infrastructure in Docker only)

```bash
# Start only the backing services
docker compose up postgres redis rabbitmq

# Build and run the app locally
./mvnw spring-boot:run
```

Spring reads env vars with safe local defaults — no `.env` file needed.

---

## Stress test

```bash
chmod +x stress-test.sh
./stress-test.sh
```

Output on success:
```
═══════════════════════════════════════════════
  Payment Bouncer — Phase 1 Stress Test
  Target : http://localhost:8080/api/v1/charge
  Shots  : 100
═══════════════════════════════════════════════

  Progress: 100/100  ✓ 100  ✗ 0

═══════════════════════════════════════════════
  Results
  Passed : 100/100
  Failed : 0/100
═══════════════════════════════════════════════
  ✅ ALL 100 REQUESTS PASSED — Phase 1 wire is solid.
═══════════════════════════════════════════════
```

---

## Validation in action

The controller rejects bad requests before any logic runs:

```bash
# Missing idempotency_key → 400
curl -s -X POST http://localhost:8080/api/v1/charge \
  -H "Content-Type: application/json" \
  -d '{"amount": 100, "currency": "INR", "merchant_reference": "x", "payment_method_token": "t"}' \
  | jq .

# {
#   "status": 400,
#   "error": "Validation failed",
#   "fields": [{ "field": "idempotencyKey", "message": "idempotency_key is required" }],
#   "timestamp": "..."
# }
```

---

## Useful URLs

| Service | URL | Credentials |
|---|---|---|
| Spring Boot app | http://localhost:8080 | — |
| Health probe | http://localhost:8080/actuator/health | — |
| RabbitMQ Management UI | http://localhost:15672 | `bouncer` / `rabbit_secret` |

---

## Package structure

```
com.bouncer
├── PaymentBouncerApplication.java     ← entry point
└── internal/                          ← nothing outside imports these
    ├── controller/
    │   └── ChargeController.java
    ├── model/
    │   ├── ChargeRequest.java         ← carried forward into every phase
    │   └── ChargeResponse.java
    └── config/
        ├── GlobalExceptionHandler.java
        └── InfrastructureConfig.java  ← Redis + RabbitMQ beans
```

> **`internal/` enforcement**: naming convention in Phase 1. Phase 2 adds an ArchUnit test that will fail the build if anything outside `com.bouncer` imports from `com.bouncer.internal`.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Connection refused` on port 5432/6379/5672 | Run `docker compose ps` — check all services are `healthy` |
| App crashes with `Unable to connect to Redis` | Redis healthcheck must pass before app starts. Check `docker compose logs redis` |
| Port conflict on 5432/6379/5672/8080 | Stop the conflicting service or change host port in `docker-compose.yml` |
| `rabbitmq-diagnostics: command not found` | Use `rabbitmq:3.13-management-alpine` (the `management` variant) — already specified |

---

## Carried into Phase 2

- `docker-compose.yml` (add migration service — Flyway)
- `ChargeRequest.java` (unchanged wire contract)
- `POST /api/v1/charge` endpoint URL (idempotency logic plugged in here)
- `InfrastructureConfig.java` (Redis template used for idempotency key storage)
