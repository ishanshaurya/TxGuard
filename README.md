# TxGuard — Transactional Safety Gateway

A production-grade, idempotent payment gateway built with Spring Boot 3, designed to prevent double charges, ensure exactly-once processing, and provide full observability into every transaction.

---

## The Problem It Solves

When a payment request is sent over a network, retries are inevitable — a timeout, a dropped connection, or a client bug can cause the same charge to be submitted multiple times. Without protection, this means double charges.

**TxGuard guarantees that no matter how many times the same request is retried, the customer is charged exactly once.**

---

## Architecture

```
Client Application
        │
        │  POST /api/v1/charge
        │  X-API-Key: <key>
        ▼
┌─────────────────────────────────────────────────────┐
│                    TxGuard                          │
│                                                     │
│  SecurityFilter ──► Rate Limit (30 req/10s)         │
│       │             Auth (X-API-Key)                │
│       ▼                                             │
│  IdempotencyService ──► Redis                       │
│       │                 (24h key cache)             │
│       ▼                                             │
│  ChargeService ──► PostgreSQL                       │
│       │            (PENDING → PROCESSING            │
│       │             → SETTLED | FAILED)             │
│       ▼                                             │
│  RabbitMQ Publisher ──► Queue                       │
│                          │                         │
│                     Consumer ──► Settle/Fail        │
│                          │       (3 retries)        │
│                        DLQ ──► Manual review        │
└─────────────────────────────────────────────────────┘
        │
        ▼
   Dashboard: http://localhost:8080
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3.3 |
| API | Spring MVC, Bean Validation |
| Idempotency | Redis 7 (SET NX, 24h TTL) |
| Persistence | PostgreSQL 16, Flyway, Spring Data JPA |
| Messaging | RabbitMQ 3.13, Spring AMQP |
| Observability | Micrometer, Prometheus, Spring Actuator |
| Security | API Key auth, sliding window rate limiter |
| Infrastructure | Docker, Docker Compose |

---

## Key Features

**Idempotency Gate**
Every request carries a unique `idempotency_key`. Redis atomically claims each key using `SET NX`. Duplicate requests with the same key return the original response instantly. Duplicate requests with a different body are rejected with `409 Conflict`.

**Async Processing Pipeline**
The HTTP layer returns immediately after persisting a `PENDING` charge and publishing to RabbitMQ. A consumer handles settlement asynchronously. Failed messages are retried 3 times with exponential backoff before routing to a dead-letter queue.

**Full Audit Trail**
Every status transition (`PENDING → PROCESSING → SETTLED | FAILED`) is recorded in `charge_status_history` — an append-only PostgreSQL table. Nothing is ever updated without a history row.

**Rate Limiting**
30 requests per 10-second sliding window per API key. Exceeding the limit returns `429 Too Many Requests`.

**Live Dashboard**
Single-page operations dashboard at `/` showing real-time charge feed, queue depth graph, component health, security counters, and an interactive charge submission panel.

---

## Quick Start

### Prerequisites
- Docker Desktop 24+
- No other dependencies needed — Maven and Java run inside the container

### Run

```bash
git clone https://github.com/YOUR_USERNAME/txguard.git
cd txguard
docker compose up --build
```

Wait for:
```
txguard-app | Started TxGuardApplication in X.XXXs
```

Open **http://localhost:8080** for the dashboard.

### Fire a charge

```bash
curl -s -X POST http://localhost:8080/api/v1/charge \
  -H "Content-Type: application/json" \
  -H "X-API-Key: bouncer-dev-key-12345" \
  -d '{
    "idempotency_key":      "order-001",
    "amount":               5000,
    "currency":             "INR",
    "merchant_reference":   "swiggy-order-9821",
    "payment_method_token": "tok_visa_4242"
  }' | python3 -m json.tool
```

### Prove idempotency works

```bash
# Send the same request twice — both return the same charge_id
KEY="test-$(date +%s)"

curl -s -X POST http://localhost:8080/api/v1/charge \
  -H "X-API-Key: bouncer-dev-key-12345" \
  -H "Content-Type: application/json" \
  -d "{\"idempotency_key\":\"$KEY\",\"amount\":1000,\"currency\":\"INR\",\"merchant_reference\":\"test\",\"payment_method_token\":\"tok\"}"

# Replay — same charge_id, no double charge
curl -s -X POST http://localhost:8080/api/v1/charge \
  -H "X-API-Key: bouncer-dev-key-12345" \
  -H "Content-Type: application/json" \
  -d "{\"idempotency_key\":\"$KEY\",\"amount\":1000,\"currency\":\"INR\",\"merchant_reference\":\"test\",\"payment_method_token\":\"tok\"}"
```

---

## API Reference

### POST /api/v1/charge

**Headers**

| Header | Required | Description |
|---|---|---|
| `X-API-Key` | Yes | API key for authentication |
| `Content-Type` | Yes | `application/json` |

**Request Body**

```json
{
  "idempotency_key":      "string (max 64 chars, unique per charge attempt)",
  "amount":               1000,
  "currency":             "INR",
  "merchant_reference":   "string (your internal order ID)",
  "payment_method_token": "string (payment instrument token)"
}
```

**Response**

```json
{
  "idempotency_key": "order-001",
  "charge_id":       "550e8400-e29b-41d4-a716-446655440000",
  "status":          "PENDING",
  "message":         "Charge accepted — processing asynchronously",
  "processed_at":    "2024-01-01T00:00:00Z"
}
```

**Status codes**

| Code | Meaning |
|---|---|
| `200` | Charge accepted or idempotent replay |
| `400` | Validation failure — check `fields` array |
| `401` | Missing or invalid API key |
| `409` | Idempotency conflict — body mismatch or key in-flight |
| `429` | Rate limit exceeded |
| `500` | Internal error |

---

## Observability Endpoints

| Endpoint | Description |
|---|---|
| `GET /actuator/health` | Full component health with detail |
| `GET /actuator/metrics` | All Micrometer metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |
| `GET /dashboard/stats` | Live dashboard stats (JSON) |
| `GET /dashboard/charges/recent` | Last 10 charges |

---

## Project Structure

```
src/main/java/com/bouncer/
├── internal/
│   ├── config/          # Spring configuration, exception handlers
│   ├── controller/      # HTTP boundary (ChargeController, DashboardDataController)
│   ├── entity/          # JPA entities (Charge, ChargeStatusHistory)
│   ├── exception/       # Domain exceptions
│   ├── messaging/       # RabbitMQ publisher + consumer
│   ├── model/           # Request/response DTOs, enums
│   ├── observability/   # Health indicators, Micrometer metrics
│   ├── repository/      # Spring Data repositories
│   ├── security/        # API key filter, rate limiter, security metrics
│   └── service/         # Business logic (ChargeService, IdempotencyService)
└── PaymentBouncerApplication.java

src/main/resources/
├── db/migration/        # Flyway SQL migrations
├── static/              # Dashboard HTML
└── application.yml      # Configuration
```

---

## Development

### Stress tests

```bash
chmod +x stress-test*.sh

./stress-test.sh             # Phase 1 — 100 basic requests
./stress-test-phase2.sh      # Phase 2 — idempotency scenarios
./stress-test-phase3.sh      # Phase 3 — persistence + UUIDs
./stress-test-phase4.sh      # Phase 4 — async settlement
./stress-test-phase5.sh      # Phase 5 — observability
./stress-test-phase6.sh      # Phase 6 — auth + rate limiting
```

### Inspect the database

```bash
docker exec -it bouncer-postgres psql -U bouncer -d bouncer \
  -c "SELECT id, status, amount, currency, created_at FROM charges ORDER BY created_at DESC LIMIT 10;"
```

### Inspect RabbitMQ

Management UI: **http://localhost:15672**
Credentials: `bouncer` / `rabbit_secret`

---

## Configuration

| Variable | Default | Description |
|---|---|---|
| `BOUNCER_API_KEY` | `bouncer-dev-key-12345` | API key for authentication |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/bouncer` | PostgreSQL URL |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis host |
| `SPRING_RABBITMQ_HOST` | `localhost` | RabbitMQ host |

---

## Built In Phases

| Phase | Feature |
|---|---|
| 1 | Spring Boot scaffold, Docker Compose stack, HTTP endpoint |
| 2 | Redis idempotency gate — exactly-once guarantee |
| 3 | PostgreSQL persistence, state machine, audit log |
| 4 | RabbitMQ async pipeline, DLQ, 3x retry with backoff |
| 5 | Micrometer metrics, health indicators, Prometheus endpoint |
| 6 | API key auth, rate limiting, live operations dashboard |

---

## License

MIT
