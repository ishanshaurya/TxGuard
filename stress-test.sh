#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# stress-test.sh — Phase 1 stress test
#
# Fires 100 POST /api/v1/charge requests at the running app.
# Prints a pass/fail summary. Exit code 0 only if ALL 100 return HTTP 200.
#
# Usage:
#   ./stress-test.sh                   # hits localhost:8080 (default)
#   BASE_URL=http://my-host:8080 ./stress-test.sh
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ENDPOINT="${BASE_URL}/api/v1/charge"
TOTAL=100
PASS=0
FAIL=0
ERRORS=()

echo "═══════════════════════════════════════════════"
echo "  Payment Bouncer — Phase 1 Stress Test"
echo "  Target : ${ENDPOINT}"
echo "  Shots  : ${TOTAL}"
echo "═══════════════════════════════════════════════"
echo ""

for i in $(seq 1 "${TOTAL}"); do
    # Generate a unique idempotency key per request
    IDEM_KEY="stress-$(printf '%04d' "${i}")-$(date +%s%N)"

    BODY=$(cat <<EOF
{
  "idempotency_key":      "${IDEM_KEY}",
  "amount":               $(( RANDOM % 9900 + 100 )),
  "currency":             "INR",
  "merchant_reference":   "stress-order-${i}",
  "payment_method_token": "tok_stress_test_4242"
}
EOF
)

    HTTP_STATUS=$(curl --silent \
                       --output /dev/null \
                       --write-out "%{http_code}" \
                       --max-time 5 \
                       -X POST "${ENDPOINT}" \
                       -H "Content-Type: application/json" \
                       -d "${BODY}")

    if [[ "${HTTP_STATUS}" == "200" ]]; then
        PASS=$(( PASS + 1 ))
        printf "\r  Progress: %d/%d  ✓ %d  ✗ %d" "${i}" "${TOTAL}" "${PASS}" "${FAIL}"
    else
        FAIL=$(( FAIL + 1 ))
        ERRORS+=("Request ${i}: HTTP ${HTTP_STATUS}")
        printf "\r  Progress: %d/%d  ✓ %d  ✗ %d  [!HTTP %s]" \
               "${i}" "${TOTAL}" "${PASS}" "${FAIL}" "${HTTP_STATUS}"
    fi
done

echo ""
echo ""
echo "═══════════════════════════════════════════════"
echo "  Results"
echo "  Passed : ${PASS}/${TOTAL}"
echo "  Failed : ${FAIL}/${TOTAL}"

if [[ "${#ERRORS[@]}" -gt 0 ]]; then
    echo ""
    echo "  Failures:"
    for err in "${ERRORS[@]}"; do
        echo "    • ${err}"
    done
fi

echo "═══════════════════════════════════════════════"

if [[ "${FAIL}" -eq 0 ]]; then
    echo "  ✅ ALL ${TOTAL} REQUESTS PASSED — Phase 1 wire is solid."
    echo "═══════════════════════════════════════════════"
    exit 0
else
    echo "  ❌ ${FAIL} REQUESTS FAILED — check logs before Phase 2."
    echo "═══════════════════════════════════════════════"
    exit 1
fi
