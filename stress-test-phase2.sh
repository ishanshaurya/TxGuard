#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# stress-test-phase2.sh — Phase 2 idempotency stress test
#
# Tests four scenarios:
#   1. 100 unique keys         → all 200 OK
#   2. 100 exact replays       → all 200 OK (cached response)
#   3. 10 body-mismatch replays → all 409 BODY_MISMATCH
#   4. Same-key race (10 concurrent) → exactly 1x 200, rest 409
#
# Exit code 0 only if ALL assertions pass.
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ENDPOINT="${BASE_URL}/api/v1/charge"

PASS=0
FAIL=0
ERRORS=()

pass() { PASS=$(( PASS + 1 )); }
fail() { FAIL=$(( FAIL + 1 )); ERRORS+=("$1"); }

check() {
    local desc="$1" expected="$2" actual="$3"
    if [[ "$actual" == "$expected" ]]; then
        pass
        printf "  ✓ %s\n" "$desc"
    else
        fail "$desc — expected HTTP $expected, got $actual"
        printf "  ✗ %s (expected %s got %s)\n" "$desc" "$expected" "$actual"
    fi
}

send() {
    local key="$1" amount="$2"
    curl --silent --output /dev/null --write-out "%{http_code}" --max-time 5 \
         -X POST "$ENDPOINT" \
         -H "Content-Type: application/json" \
         -d "{
               \"idempotency_key\":      \"$key\",
               \"amount\":               $amount,
               \"currency\":             \"INR\",
               \"merchant_reference\":   \"order-$key\",
               \"payment_method_token\": \"tok_stress_4242\"
             }"
}

echo ""
echo "═══════════════════════════════════════════════════════"
echo "  Payment Bouncer — Phase 2 Idempotency Stress Test"
echo "  Target: $ENDPOINT"
echo "═══════════════════════════════════════════════════════"

# ── Scenario 1: 100 unique keys ─────────────────────────────────────────────
echo ""
echo "[ Scenario 1 ] 100 unique keys → expect 200 each"
for i in $(seq 1 100); do
    KEY="s1-unique-$(printf '%04d' $i)-$$"
    STATUS=$(send "$KEY" 1000)
    check "Unique key $i" "200" "$STATUS"
done

# ── Scenario 2: 100 exact replays ───────────────────────────────────────────
echo ""
echo "[ Scenario 2 ] 100 exact replays → expect 200 (cached) each"
REPLAY_KEY="s2-replay-key-$$"
# First request
STATUS=$(send "$REPLAY_KEY" 2000)
check "Replay — first request" "200" "$STATUS"
# 99 replays
for i in $(seq 2 100); do
    STATUS=$(send "$REPLAY_KEY" 2000)
    check "Replay $i" "200" "$STATUS"
done

# ── Scenario 3: Body mismatch replays ───────────────────────────────────────
echo ""
echo "[ Scenario 3 ] Body mismatch replays → expect 409 each"
for i in $(seq 1 10); do
    MISMATCH_KEY="s3-mismatch-$(printf '%02d' $i)-$$"
    # First request with amount 500
    send "$MISMATCH_KEY" 500 > /dev/null
    # Second request with different amount → should 409
    STATUS=$(send "$MISMATCH_KEY" 999)
    check "Body mismatch $i" "409" "$STATUS"
done

# ── Scenario 4: Concurrent race on same key ──────────────────────────────────
echo ""
echo "[ Scenario 4 ] 10 concurrent requests with same key → exactly 1x 200"
RACE_KEY="s4-race-key-$$"
RACE_DIR=$(mktemp -d)

for i in $(seq 1 10); do
    (
        STATUS=$(send "$RACE_KEY" 7500)
        echo "$STATUS" > "$RACE_DIR/result-$i"
    ) &
done
wait

COUNT_200=0
COUNT_409=0
COUNT_OTHER=0
for f in "$RACE_DIR"/result-*; do
    code=$(cat "$f")
    case "$code" in
        200) COUNT_200=$(( COUNT_200 + 1 ));;
        409) COUNT_409=$(( COUNT_409 + 1 ));;
        *)   COUNT_OTHER=$(( COUNT_OTHER + 1 ));;
    esac
done
rm -rf "$RACE_DIR"

printf "     200s: %d  409s: %d  other: %d\n" "$COUNT_200" "$COUNT_409" "$COUNT_OTHER"

if [[ "$COUNT_200" -eq 1 && "$COUNT_OTHER" -eq 0 ]]; then
    pass
    echo "  ✓ Race test — exactly 1 winner, rest bounced"
else
    fail "Race test — expected exactly 1x 200, got ${COUNT_200}x 200, ${COUNT_OTHER}x other"
    echo "  ✗ Race test"
fi

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════"
echo "  Results"
echo "  Passed : $PASS"
echo "  Failed : $FAIL"

if [[ "${#ERRORS[@]}" -gt 0 ]]; then
    echo ""
    echo "  Failures:"
    for err in "${ERRORS[@]}"; do
        echo "    • $err"
    done
fi

echo "═══════════════════════════════════════════════════════"

if [[ "$FAIL" -eq 0 ]]; then
    echo "  ✅ ALL SCENARIOS PASSED — Phase 2 bouncer is solid."
    echo "═══════════════════════════════════════════════════════"
    exit 0
else
    echo "  ❌ $FAIL CHECKS FAILED"
    echo "═══════════════════════════════════════════════════════"
    exit 1
fi
