#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# stress-test-phase5.sh — Phase 5 observability verification
#
# Verifies:
#   1. /actuator/health returns UP with all detailed components
#   2. RabbitMQ queue depth visible in health output
#   3. Redis latency visible in health output
#   4. DB connection pool stats visible in health output
#   5. Charge metrics exist at /actuator/metrics
#   6. Queue depth gauge registered at /actuator/metrics/rabbitmq.queue.messages
#   7. Fire 20 charges, verify metrics counters increment
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0; FAIL=0; ERRORS=()

pass() { PASS=$(( PASS + 1 )); printf "  ✓ %s\n" "$1"; }
fail() { FAIL=$(( FAIL + 1 )); ERRORS+=("$1"); printf "  ✗ %s\n" "$1"; }

get_json() { curl --silent --max-time 5 "$1"; }
get_field() { echo "$1" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$2','MISSING'))" 2>/dev/null || echo "ERR"; }
nested()  {
    echo "$1" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    keys = '$2'.split('.')
    for k in keys:
        d = d[k]
    print(d)
except:
    print('MISSING')
" 2>/dev/null
}

send_charge() {
    local key="$1"
    curl --silent --max-time 5 -X POST "$BASE_URL/api/v1/charge" \
         -H "Content-Type: application/json" \
         -d "{\"idempotency_key\":\"$key\",\"amount\":1000,\"currency\":\"INR\",\"merchant_reference\":\"order-$key\",\"payment_method_token\":\"tok_p5\"}"
}

echo ""
echo "══════════════════════════════════════════════════════"
echo "  Payment Bouncer — Phase 5 Observability Test"
echo "  Target: $BASE_URL"
echo "══════════════════════════════════════════════════════"

# ── 1. Overall health ─────────────────────────────────────────────────────────
echo ""
echo "[ 1 ] Overall health check"
HEALTH=$(get_json "$BASE_URL/actuator/health")
STATUS=$(get_field "$HEALTH" "status")
if [[ "$STATUS" == "UP" ]]; then pass "Overall status is UP"
else fail "Overall status — expected UP got $STATUS"; fi

# ── 2. RabbitMQ queue health component ───────────────────────────────────────
echo ""
echo "[ 2 ] RabbitMQ queue health"
RABBIT_STATUS=$(nested "$HEALTH" "components.rabbitQueues.status")
if [[ "$RABBIT_STATUS" == "UP" ]]; then pass "rabbitQueues component is UP"
else fail "rabbitQueues — expected UP got $RABBIT_STATUS"; fi

MAIN_DEPTH=$(nested "$HEALTH" "components.rabbitQueues.details.bouncer\.charges\.received.message_count")
if [[ "$MAIN_DEPTH" != "MISSING" ]]; then
    pass "Queue depth visible: bouncer.charges.received = $MAIN_DEPTH messages"
else fail "Queue depth not visible in health output"; fi

DLQ_DEPTH=$(nested "$HEALTH" "components.rabbitQueues.details.bouncer\.charges\.received\.dlq.message_count")
if [[ "$DLQ_DEPTH" != "MISSING" ]]; then
    pass "DLQ depth visible: $DLQ_DEPTH messages"
else fail "DLQ depth not visible in health output"; fi

# ── 3. Redis health component ──────────────────────────────────────────────────
echo ""
echo "[ 3 ] Redis detail health"
REDIS_STATUS=$(nested "$HEALTH" "components.redisDetail.status")
if [[ "$REDIS_STATUS" == "UP" ]]; then pass "redisDetail component is UP"
else fail "redisDetail — expected UP got $REDIS_STATUS"; fi

REDIS_LATENCY=$(nested "$HEALTH" "components.redisDetail.details.ping_latency_ms")
if [[ "$REDIS_LATENCY" != "MISSING" ]]; then
    pass "Redis latency visible: ${REDIS_LATENCY}ms"
else fail "Redis latency not visible"; fi

# ── 4. Database health component ──────────────────────────────────────────────
echo ""
echo "[ 4 ] Database detail health"
DB_STATUS=$(nested "$HEALTH" "components.databaseDetail.status")
if [[ "$DB_STATUS" == "UP" ]]; then pass "databaseDetail component is UP"
else fail "databaseDetail — expected UP got $DB_STATUS"; fi

POOL_TOTAL=$(nested "$HEALTH" "components.databaseDetail.details.pool_total_connections")
if [[ "$POOL_TOTAL" != "MISSING" ]]; then
    pass "Connection pool visible: $POOL_TOTAL total connections"
else fail "Connection pool stats not visible"; fi

# ── 5. Charge metrics exist ───────────────────────────────────────────────────
echo ""
echo "[ 5 ] Charge metrics registered"
METRICS=$(get_json "$BASE_URL/actuator/metrics")
for metric in "bouncer.charges.initiated" "bouncer.charges.settled" "bouncer.charges.replayed" "bouncer.charge.settle.time"; do
    if echo "$METRICS" | python3 -c "import sys,json; names=json.load(sys.stdin)['names']; exit(0 if '$metric' in names else 1)" 2>/dev/null; then
        pass "Metric registered: $metric"
    else
        fail "Metric missing: $metric"
    fi
done

# ── 6. Queue depth gauge ──────────────────────────────────────────────────────
echo ""
echo "[ 6 ] Queue depth gauge"
QUEUE_METRIC=$(get_json "$BASE_URL/actuator/metrics/rabbitmq.queue.messages" 2>/dev/null || echo "{}")
QUEUE_NAME=$(nested "$QUEUE_METRIC" "availableTags.0.values.0" 2>/dev/null || echo "MISSING")
if echo "$QUEUE_METRIC" | python3 -c "import sys,json; d=json.load(sys.stdin); exit(0 if 'measurements' in d else 1)" 2>/dev/null; then
    pass "rabbitmq.queue.messages gauge is registered"
else fail "rabbitmq.queue.messages gauge missing"; fi

# ── 7. Fire 20 charges and check counters increment ──────────────────────────
echo ""
echo "[ 7 ] Fire 20 charges — verify counters increment"

BEFORE=$(get_json "$BASE_URL/actuator/metrics/bouncer.charges.initiated")
BEFORE_COUNT=$(nested "$BEFORE" "measurements.0.value")

for i in $(seq 1 20); do
    send_charge "p5-$(printf '%04d' $i)-$$" > /dev/null
done

sleep 2  # wait for metrics to update

AFTER=$(get_json "$BASE_URL/actuator/metrics/bouncer.charges.initiated")
AFTER_COUNT=$(nested "$AFTER" "measurements.0.value")

DIFF=$(python3 -c "print(int($AFTER_COUNT) - int($BEFORE_COUNT))" 2>/dev/null || echo "ERR")
if [[ "$DIFF" == "20" ]]; then
    pass "bouncer.charges.initiated incremented by 20 (was $BEFORE_COUNT, now $AFTER_COUNT)"
else
    fail "Counter increment wrong — expected +20, got +$DIFF (before=$BEFORE_COUNT after=$AFTER_COUNT)"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════"
echo "  Passed : $PASS   Failed : $FAIL"
if [[ "${#ERRORS[@]}" -gt 0 ]]; then
    echo "  Failures:"
    for e in "${ERRORS[@]}"; do echo "    • $e"; done
fi
echo "══════════════════════════════════════════════════════"
if [[ "$FAIL" -eq 0 ]]; then
    echo "  ✅ ALL PASSED — Phase 5 observability is solid."
else
    echo "  ❌ $FAIL CHECKS FAILED"
fi
echo "══════════════════════════════════════════════════════"
[[ "$FAIL" -eq 0 ]]
