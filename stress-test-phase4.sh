#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# stress-test-phase4.sh — Phase 4 async event pipeline stress test
#
# Tests:
#   1. 50 charges return PENDING immediately (async accepted)
#   2. After a short wait, all 50 are SETTLED in the DB
#   3. Replays return cached PENDING response (idempotency intact)
#   4. Body mismatch still returns 409
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ENDPOINT="${BASE_URL}/api/v1/charge"
PASS=0; FAIL=0; ERRORS=()

pass() { PASS=$(( PASS + 1 )); }
fail() { FAIL=$(( FAIL + 1 )); ERRORS+=("$1"); }

send() {
    local key="$1" amount="$2"
    curl --silent --max-time 5 \
         -X POST "$ENDPOINT" \
         -H "Content-Type: application/json" \
         -d "{\"idempotency_key\":\"$key\",\"amount\":$amount,\"currency\":\"INR\",\"merchant_reference\":\"order-$key\",\"payment_method_token\":\"tok_4242\"}"
}

get_field() { echo "$1" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$2','MISSING'))" 2>/dev/null || echo "ERR"; }
is_uuid()   { echo "$1" | grep -qE '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'; }

check_db_status() {
    local charge_id="$1"
    docker exec bouncer-postgres psql -U bouncer -d bouncer -tAc \
        "SELECT status FROM charges WHERE id='$charge_id';" 2>/dev/null | tr -d '[:space:]'
}

TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

echo ""
echo "══════════════════════════════════════════════════════"
echo "  Payment Bouncer — Phase 4 Async Pipeline Test"
echo "  Target: $ENDPOINT"
echo "══════════════════════════════════════════════════════"

# ── Scenario 1: 50 charges → all return PENDING immediately ───────────────────
echo ""
echo "[ Scenario 1 ] 50 charges → HTTP returns PENDING immediately"
for i in $(seq 1 50); do
    KEY="p4-s1-$(printf '%04d' $i)-$$"
    RESP=$(send "$KEY" 2000)
    STATUS=$(get_field "$RESP" "status")
    CID=$(get_field "$RESP" "charge_id")

    if [[ "$STATUS" == "PENDING" ]] && is_uuid "$CID"; then
        pass; printf "  ✓ Charge %d — PENDING %s\n" "$i" "$CID"
        echo "$KEY $CID" >> "$TMPFILE"
    else
        fail "Charge $i — expected PENDING UUID, got status=$STATUS charge_id=$CID"
        printf "  ✗ Charge %d\n" "$i"
    fi
done

# ── Scenario 2: Wait for async settlement, then check DB ─────────────────────
echo ""
echo "[ Scenario 2 ] Waiting 3s for async consumer to settle all charges..."
sleep 3

echo "  Checking DB status for all charges..."
SETTLED=0; NOT_SETTLED=0
while IFS=' ' read -r KEY CID; do
    DB_STATUS=$(check_db_status "$CID")
    if [[ "$DB_STATUS" == "SETTLED" ]]; then
        SETTLED=$(( SETTLED + 1 ))
        pass
    else
        NOT_SETTLED=$(( NOT_SETTLED + 1 ))
        fail "Charge $CID — DB status is '$DB_STATUS', expected SETTLED"
    fi
done < "$TMPFILE"
printf "  Settled: %d  Not settled: %d\n" "$SETTLED" "$NOT_SETTLED"

# ── Scenario 3: Replay same keys → PENDING cached response ───────────────────
echo ""
echo "[ Scenario 3 ] Replay 10 keys → cached PENDING response"
head -10 "$TMPFILE" | while IFS=' ' read -r KEY CID; do
    RESP=$(send "$KEY" 2000)
    REPLAYED_CID=$(get_field "$RESP" "charge_id")
    REPLAYED_STATUS=$(get_field "$RESP" "status")

    if [[ "$REPLAYED_CID" == "$CID" && "$REPLAYED_STATUS" == "PENDING" ]]; then
        pass; printf "  ✓ Replay — same charge_id %s\n" "$CID"
    else
        fail "Replay mismatch — cid=$REPLAYED_CID status=$REPLAYED_STATUS"
        printf "  ✗ Replay mismatch\n"
    fi
done

# ── Scenario 4: Body mismatch → 409 ──────────────────────────────────────────
echo ""
echo "[ Scenario 4 ] Body mismatch → 409"
for i in $(seq 1 5); do
    KEY="p4-s4-$(printf '%02d' $i)-$$"
    send "$KEY" 500 > /dev/null
    RESP=$(send "$KEY" 999)
    CODE=$(get_field "$RESP" "status")
    if [[ "$CODE" == "409" ]]; then
        pass; printf "  ✓ Body mismatch %d → 409\n" "$i"
    else
        fail "Mismatch $i — expected 409, got $CODE"
        printf "  ✗ Mismatch %d\n" "$i"
    fi
done

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
    echo "  ✅ ALL PASSED — Phase 4 async pipeline is solid."
else
    echo "  ❌ $FAIL CHECKS FAILED"
fi
echo "══════════════════════════════════════════════════════"
[[ "$FAIL" -eq 0 ]]
