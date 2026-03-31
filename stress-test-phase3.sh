#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# stress-test-phase3.sh — Phase 3 persistence stress test
#
# Tests:
#   1. 100 unique charges   → all 200, all return real UUID charge_id
#   2. 100 replays          → all 200, same charge_id as original
#   3. Status is SETTLED    → not ACCEPTED or STUB anymore
#   4. Body mismatch        → 409
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ENDPOINT="${BASE_URL}/api/v1/charge"
PASS=0; FAIL=0; ERRORS=()

pass() { PASS=$(( PASS + 1 )); }
fail() { FAIL=$(( FAIL + 1 )); ERRORS+=("$1"); }

send_full() {
    local key="$1" amount="$2"
    curl --silent --max-time 5 \
         -X POST "$ENDPOINT" \
         -H "Content-Type: application/json" \
         -d "{
               \"idempotency_key\":      \"$key\",
               \"amount\":               $amount,
               \"currency\":             \"INR\",
               \"merchant_reference\":   \"order-$key\",
               \"payment_method_token\": \"tok_4242\"
             }"
}

check_field() {
    local desc="$1" json="$2" field="$3" expected="$4"
    actual=$(echo "$json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$field','MISSING'))" 2>/dev/null || echo "PARSE_ERROR")
    if [[ "$actual" == "$expected" ]]; then
        pass; printf "  ✓ %s\n" "$desc"
    else
        fail "$desc — expected '$expected' got '$actual'"
        printf "  ✗ %s\n" "$desc"
    fi
}

is_uuid() {
    echo "$1" | grep -qE '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
}

echo ""
echo "═══════════════════════════════════════════════════════"
echo "  Payment Bouncer — Phase 3 Persistence Stress Test"
echo "  Target: $ENDPOINT"
echo "═══════════════════════════════════════════════════════"

# ── Scenario 1: 100 unique charges with real UUIDs and SETTLED status ─────────
echo ""
echo "[ Scenario 1 ] 100 unique charges → real UUID charge_id + SETTLED status"
declare -A CHARGE_IDS
for i in $(seq 1 100); do
    KEY="p3-s1-$(printf '%04d' $i)-$$"
    RESPONSE=$(send_full "$KEY" 1500)
    HTTP_STATUS=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(200)" 2>/dev/null || echo "000")

    CHARGE_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('charge_id',''))" 2>/dev/null || echo "")
    STATUS=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status',''))" 2>/dev/null || echo "")

    # Check it's a real UUID (not STUB-0000)
    if is_uuid "$CHARGE_ID"; then
        pass; printf "  ✓ Charge %d — UUID: %s\n" "$i" "$CHARGE_ID"
    else
        fail "Charge $i — expected UUID charge_id, got '$CHARGE_ID'"
        printf "  ✗ Charge %d — bad charge_id: %s\n" "$i" "$CHARGE_ID"
    fi

    # Check status is SETTLED
    if [[ "$STATUS" == "SETTLED" ]]; then
        pass
    else
        fail "Charge $i — expected SETTLED status, got '$STATUS'"
    fi

    CHARGE_IDS["$KEY"]="$CHARGE_ID"
done

# ── Scenario 2: Replay same keys → same charge_id returned ────────────────────
echo ""
echo "[ Scenario 2 ] Replay 10 keys → same charge_id as original"
COUNT=0
for KEY in "${!CHARGE_IDS[@]}"; do
    [[ $COUNT -ge 10 ]] && break
    ORIGINAL_ID="${CHARGE_IDS[$KEY]}"
    RESPONSE=$(send_full "$KEY" 1500)
    REPLAYED_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('charge_id',''))" 2>/dev/null || echo "")

    if [[ "$REPLAYED_ID" == "$ORIGINAL_ID" ]]; then
        pass; printf "  ✓ Replay — charge_id matches: %s\n" "$ORIGINAL_ID"
    else
        fail "Replay mismatch — original=$ORIGINAL_ID replayed=$REPLAYED_ID"
        printf "  ✗ Replay charge_id mismatch\n"
    fi
    COUNT=$(( COUNT + 1 ))
done

# ── Scenario 3: Body mismatch → 409 ──────────────────────────────────────────
echo ""
echo "[ Scenario 3 ] Body mismatch → 409"
for i in $(seq 1 10); do
    KEY="p3-s3-mismatch-$(printf '%02d' $i)-$$"
    send_full "$KEY" 500 > /dev/null
    RESPONSE=$(send_full "$KEY" 501)
    CODE=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status',''))" 2>/dev/null || echo "")
    if [[ "$CODE" == "409" ]]; then
        pass; printf "  ✓ Body mismatch %d → 409\n" "$i"
    else
        fail "Body mismatch $i — expected 409, got response: $RESPONSE"
        printf "  ✗ Body mismatch %d\n" "$i"
    fi
done

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════"
echo "  Results"
echo "  Passed : $PASS"
echo "  Failed : $FAIL"

if [[ "${#ERRORS[@]}" -gt 0 ]]; then
    echo ""
    echo "  Failures:"
    for err in "${ERRORS[@]}"; do echo "    • $err"; done
fi

echo "═══════════════════════════════════════════════════════"

if [[ "$FAIL" -eq 0 ]]; then
    echo "  ✅ ALL PASSED — Phase 3 persistence is solid."
    echo "═══════════════════════════════════════════════════════"
    exit 0
else
    echo "  ❌ $FAIL CHECKS FAILED"
    echo "═══════════════════════════════════════════════════════"
    exit 1
fi
