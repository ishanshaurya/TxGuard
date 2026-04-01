#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# stress-test-phase6.sh — Phase 6 hardening + dashboard test
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="txguard-dev-key-12345"
PASS=0; FAIL=0; ERRORS=()

pass() { PASS=$(( PASS + 1 )); printf "  ✓ %s\n" "$1"; }
fail() { FAIL=$(( FAIL + 1 )); ERRORS+=("$1"); printf "  ✗ %s\n" "$1"; }

charge() {
    local key="$1" amount="$2" api_key="${3:-$API_KEY}"
    curl --silent --max-time 5 --write-out "\n%{http_code}" \
         -X POST "$BASE_URL/api/v1/charge" \
         -H "Content-Type: application/json" \
         -H "X-API-Key: $api_key" \
         -d "{\"idempotency_key\":\"$key\",\"amount\":$amount,\"currency\":\"INR\",\"merchant_reference\":\"order-$key\",\"payment_method_token\":\"tok_p6\"}"
}

get_code() { echo "$1" | tail -1; }
get_body() { echo "$1" | head -1; }
get_field() { echo "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('$2',''))" 2>/dev/null || echo ""; }

echo ""
echo "══════════════════════════════════════════════════════"
echo "  Payment Bouncer — Phase 6 Hardening Test"
echo "  Target: $BASE_URL"
echo "══════════════════════════════════════════════════════"

# ── 1. Valid API key → 200 ────────────────────────────────────────────────────
echo ""
echo "[ 1 ] Valid API key → 200"
RESP=$(charge "p6-valid-$$" 1000)
CODE=$(get_code "$RESP")
[[ "$CODE" == "200" ]] && pass "Valid key accepted (200)" || fail "Valid key rejected ($CODE)"

# ── 2. Missing API key → 401 ──────────────────────────────────────────────────
echo ""
echo "[ 2 ] Missing API key → 401"
CODE=$(curl --silent --max-time 5 --write-out "%{http_code}" --output /dev/null \
       -X POST "$BASE_URL/api/v1/charge" \
       -H "Content-Type: application/json" \
       -d '{"idempotency_key":"no-key","amount":100,"currency":"INR","merchant_reference":"test","payment_method_token":"tok"}')
[[ "$CODE" == "401" ]] && pass "Missing key rejected (401)" || fail "Expected 401 got $CODE"

# ── 3. Wrong API key → 401 ────────────────────────────────────────────────────
echo ""
echo "[ 3 ] Wrong API key → 401"
RESP=$(charge "p6-bad-key-$$" 1000 "totally-wrong-key")
CODE=$(get_code "$RESP")
[[ "$CODE" == "401" ]] && pass "Wrong key rejected (401)" || fail "Expected 401 got $CODE"

# ── 4. Rate limiting → 429 after 30 requests ─────────────────────────────────
echo ""
echo "[ 4 ] Rate limiting → expect 429 after 30 requests"
BLOCKED=0
for i in $(seq 1 35); do
    CODE=$(curl --silent --max-time 5 --write-out "%{http_code}" --output /dev/null \
           -X POST "$BASE_URL/api/v1/charge" \
           -H "Content-Type: application/json" \
           -H "X-API-Key: $API_KEY" \
           -d "{\"idempotency_key\":\"p6-rl-$i-$$\",\"amount\":100,\"currency\":\"INR\",\"merchant_reference\":\"rl-test\",\"payment_method_token\":\"tok\"}")
    [[ "$CODE" == "429" ]] && BLOCKED=$(( BLOCKED + 1 ))
done
printf "  Blocked: %d/35\n" "$BLOCKED"
[[ "$BLOCKED" -ge 1 ]] && pass "Rate limiter triggered ($BLOCKED requests blocked)" || fail "Rate limiter never triggered"

# ── 5. Actuator accessible without API key ────────────────────────────────────
echo ""
echo "[ 5 ] Actuator accessible without API key"
CODE=$(curl --silent --max-time 5 --write-out "%{http_code}" --output /dev/null "$BASE_URL/actuator/health")
[[ "$CODE" == "200" ]] && pass "Actuator accessible (no key needed)" || fail "Actuator returned $CODE"

# ── 6. Dashboard accessible without API key ───────────────────────────────────
echo ""
echo "[ 6 ] Dashboard accessible without API key"
CODE=$(curl --silent --max-time 5 --write-out "%{http_code}" --output /dev/null "$BASE_URL/")
[[ "$CODE" == "200" ]] && pass "Dashboard served (200)" || fail "Dashboard returned $CODE"

# ── 7. Dashboard stats endpoint works ────────────────────────────────────────
echo ""
echo "[ 7 ] Dashboard stats endpoint"
STATS=$(curl --silent --max-time 5 "$BASE_URL/dashboard/stats")
SETTLED=$(get_field "$STATS" "charges_settled")
[[ -n "$SETTLED" ]] && pass "Dashboard stats returning data (settled=$SETTLED)" || fail "Dashboard stats broken"

# ── 8. Security metrics increment in stats ────────────────────────────────────
echo ""
echo "[ 8 ] Auth failure counter visible in dashboard stats"
AUTH_FAIL=$(get_field "$STATS" "auth_failures")
[[ "$AUTH_FAIL" -ge 1 ]] && pass "Auth failures tracked ($AUTH_FAIL total)" || fail "Auth failure counter is 0 or missing"

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════"
echo "  Passed : $PASS   Failed : $FAIL"
if [[ "${#ERRORS[@]}" -gt 0 ]]; then
    echo "  Failures:"; for e in "${ERRORS[@]}"; do echo "    • $e"; done
fi
echo "══════════════════════════════════════════════════════"
if [[ "$FAIL" -eq 0 ]]; then
    echo "  ✅ ALL PASSED — Phase 6 hardening is solid."
    echo ""
    echo "  🎯 Dashboard: http://localhost:8080"
else
    echo "  ❌ $FAIL CHECKS FAILED"
fi
echo "══════════════════════════════════════════════════════"
[[ "$FAIL" -eq 0 ]]
