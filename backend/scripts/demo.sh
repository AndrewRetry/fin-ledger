#!/usr/bin/env bash
# Walks every assignment requirement against a running ledger and prints PASS/FAIL per step.
# Usage: ./scripts/demo.sh                      (after `docker compose up --build`)
#        BASE_URL=http://localhost:8080 ADMIN_KEY=dev-admin ./scripts/demo.sh
# Needs: bash, curl, jq. Exit code = number of failed steps. Creates its own accounts, so it can run repeatedly.
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_KEY="${ADMIN_KEY:-dev-admin}"
command -v jq >/dev/null || { echo "jq is required"; exit 1; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
pass=0; fail=0
uuid() { if [[ -r /proc/sys/kernel/random/uuid ]]; then cat /proc/sys/kernel/random/uuid; else uuidgen | tr 'A-Z' 'a-z'; fi; }

# call METHOD PATH [BODY] [IDEMPOTENCY_KEY] [EXTRA_HEADER] -> sets STATUS, BODY, HEADERS
call() {
  local method=$1 path=$2 body=${3:-} key=${4:-} extra=${5:-}
  local args=(-sS --max-time 60 -X "$method" -H "Content-Type: application/json" -D "$TMP/h" -o "$TMP/b" -w '%{http_code}')
  [[ -n "$key" ]] && args+=(-H "Idempotency-Key: $key")
  [[ -n "$extra" ]] && args+=(-H "$extra")
  [[ -n "$body" ]] && args+=(--data "$body")
  STATUS=$(curl "${args[@]}" "$BASE_URL$path"); BODY=$(cat "$TMP/b"); HEADERS=$(cat "$TMP/h")
}
check() { # NAME ACTUAL EXPECTED
  if [[ "$2" == "$3" ]]; then echo "PASS  $1"; pass=$((pass+1));
  else echo "FAIL  $1 (expected '$3', got '$2')"; echo "      $(echo "$BODY" | head -c 400)"; fail=$((fail+1)); fi
}
balance() { call GET "/accounts/$1"; echo "$BODY" | jq -r '.balance.amount'; }
code() { echo "$STATUS $(echo "$BODY" | jq -r '.code // empty')"; }

echo "== 0. Health"
STATUS=$(curl -sS --max-time 60 -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health/liveness")
check "service is up" "$STATUS" "200"

echo "== 1. Accounts (amounts are integer minor units: 2500 = SGD 25.00)"
call POST /accounts '{"name":"Demo Alice","currency":"SGD"}'; check "create Alice (SGD)" "$STATUS" "201"; ALICE=$(echo "$BODY" | jq -r .id)
call POST /accounts '{"name":"Demo Bob","currency":"SGD"}';   check "create Bob (SGD)"   "$STATUS" "201"; BOB=$(echo "$BODY" | jq -r .id)
call POST /accounts '{"name":"Demo Carol","currency":"USD"}'; check "create Carol (USD)" "$STATUS" "201"; CAROL=$(echo "$BODY" | jq -r .id)

echo "== 2. Deposit SGD 1,000.00 into Alice"
call POST /deposits "{\"accountId\":\"$ALICE\",\"amount\":100000,\"description\":\"demo top-up\"}" "$(uuid)"
check "deposit" "$STATUS" "201"
check "Alice balance is 100000" "$(balance "$ALICE")" "100000"

echo "== 3. Atomic transfer, then the same request again"
K1=$(uuid)
call POST /transactions "{\"sourceAccountId\":\"$ALICE\",\"destinationAccountId\":\"$BOB\",\"amount\":2500,\"description\":\"Lunch split\"}" "$K1"
check "transfer SGD 25.00 Alice -> Bob" "$STATUS" "201"; T1=$(echo "$BODY" | jq -r .id)
check "confirmation: status POSTED" "$(echo "$BODY" | jq -r .status)" "POSTED"
check "two postings (debit Alice, credit Bob)" "$(echo "$BODY" | jq '.postings | length')" "2"
call POST /transactions "{\"sourceAccountId\":\"$ALICE\",\"destinationAccountId\":\"$BOB\",\"amount\":2500,\"description\":\"Lunch split\"}" "$K1"
check "same key + same body -> 201 replay" "$STATUS" "201"
check "replay returns the same transaction id" "$(echo "$BODY" | jq -r .id)" "$T1"
check "replay is flagged" "$(echo "$HEADERS" | grep -ci '^idempotent-replayed: true')" "1"
call POST /transactions "{\"sourceAccountId\":\"$ALICE\",\"destinationAccountId\":\"$BOB\",\"amount\":9999}" "$K1"
check "same key + different body -> 422" "$(code)" "422 IDEMPOTENCY_KEY_REUSED"
check "Alice debited exactly once" "$(balance "$ALICE")" "97500"
check "Bob credited exactly once"  "$(balance "$BOB")" "2500"

echo "== 4. Rejections (nothing is written)"
call POST /transactions "{\"sourceAccountId\":\"$BOB\",\"destinationAccountId\":\"$ALICE\",\"amount\":100000000}" "$(uuid)"
check "insufficient funds -> 422" "$(code)" "422 INSUFFICIENT_FUNDS"
call POST /transactions "{\"sourceAccountId\":\"$ALICE\",\"destinationAccountId\":\"$ALICE\",\"amount\":100}" "$(uuid)"
check "same account -> 422" "$(code)" "422 SAME_ACCOUNT"
call POST /transactions "{\"sourceAccountId\":\"$ALICE\",\"destinationAccountId\":\"$BOB\",\"amount\":10.5}" "$(uuid)"
check "fractional amount -> 400" "$(code)" "400 VALIDATION_FAILED"
call POST /transactions "{\"sourceAccountId\":\"$ALICE\",\"destinationAccountId\":\"$BOB\",\"amount\":100}"
check "missing Idempotency-Key -> 400" "$(code)" "400 IDEMPOTENCY_KEY_MISSING"
check "balances unchanged" "$(balance "$ALICE")/$(balance "$BOB")" "97500/2500"

echo "== 5. History (ordered by time, oldest first)"
call GET "/accounts/$ALICE/transactions?page=0&size=50"
check "history status" "$STATUS" "200"
check "first entry is the deposit" "$(echo "$BODY" | jq -r '.items[0].type')" "DEPOSIT"
check "second entry is the transfer" "$(echo "$BODY" | jq -r '.items[1].transactionId')" "$T1"
check "running balance after the transfer" "$(echo "$BODY" | jq -r '.items[1].balanceAfter.amount')" "97500"

echo "== 6. Cross-currency transfer SGD -> USD"
call GET /fx/rates
RATE=$(echo "$BODY" | jq -r '[.[] | select(.base=="SGD" and .quote=="USD")][0].rate')
echo "      SGD->USD rate in use: $RATE"
call POST /transactions "{\"sourceAccountId\":\"$ALICE\",\"destinationAccountId\":\"$CAROL\",\"amount\":10000}" "$(uuid)"
check "FX transfer SGD 100.00 -> Carol (USD)" "$STATUS" "201"; FX1=$(echo "$BODY" | jq -r .id)
check "FX transfer has 4 postings (via house FX wallets)" "$(echo "$BODY" | jq '.postings | length')" "4"
CREDITED=$(echo "$BODY" | jq -r --arg c "$CAROL" '.postings[] | select(.accountId==$c) | .amount.amount')
check "Carol received the converted amount (rounded down)" "$(balance "$CAROL")" "$CREDITED"
echo "      Carol received USD cents: $CREDITED"

echo "== 7. Reversals"
call POST "/transactions/$T1/reversals" '{"reason":"customer dispute"}' "$(uuid)"
check "reverse the Alice -> Bob transfer" "$STATUS" "201"
call POST "/transactions/$T1/reversals" '{"reason":"again"}' "$(uuid)"
check "second reversal -> 409" "$(code)" "409 ALREADY_REVERSED"
call POST "/transactions/$FX1/reversals" '{"reason":"wrong recipient"}' "$(uuid)"
check "reverse the FX transfer (at the original rate)" "$STATUS" "201"
check "Alice back to SGD 1,000.00" "$(balance "$ALICE")" "100000"
call GET "/transactions/$T1"
check "original links to its reversal" "$(echo "$BODY" | jq -r '.reversedById != null')" "true"

echo "== 8. 20 simultaneous transfers into the same account"
BEFORE_BOB=$(balance "$BOB")
seq 1 20 | xargs -P 20 -I{} sh -c "curl -sS -o /dev/null -w '%{http_code}\n' -X POST '$BASE_URL/transactions' \
  -H 'Content-Type: application/json' -H \"Idempotency-Key: burst-\$(date +%s%N)-{}\" \
  --data '{\"sourceAccountId\":\"$ALICE\",\"destinationAccountId\":\"$BOB\",\"amount\":100}'" > "$TMP/burst"
check "all 20 accepted" "$(grep -c '^201$' "$TMP/burst")" "20"
check "Bob received exactly 20 x 1.00 (no lost updates)" "$(balance "$BOB")" "$((BEFORE_BOB + 2000))"
check "Alice paid exactly 20 x 1.00" "$(balance "$ALICE")" "98000"

echo "== 9. One outside deposit, booked once (externalRef = e.g. a Fireblocks transaction id)"
EXT="fb-demo-$(date +%s)-$RANDOM"
BEFORE_BOB=$(balance "$BOB")
call POST /deposits "{\"accountId\":\"$BOB\",\"amount\":5000,\"description\":\"incoming transfer\",\"externalRef\":\"$EXT\"}" "$(uuid)"
check "deposit with an external reference" "$STATUS" "201"
check "external reference returned" "$(echo "$BODY" | jq -r .externalRef)" "$EXT"
call POST /deposits "{\"accountId\":\"$BOB\",\"amount\":5000,\"description\":\"same event, delivered again\",\"externalRef\":\"$EXT\"}" "$(uuid)"
check "same reference under a new key -> 409" "$(code)" "409 EXTERNAL_REF_ALREADY_USED"
check "Bob credited once" "$(balance "$BOB")" "$((BEFORE_BOB + 5000))"

echo "== 10. Integrity, hash chain, month-end"
call POST /integrity/checks
check "integrity run is CLEAN" "$(echo "$BODY" | jq -r .status)" "CLEAN"
check "money in wallets = money deposited, every currency" "$(echo "$BODY" | jq '[.moneySupply[].matches] | all')" "true"
check "hash chain is valid" "$(echo "$BODY" | jq -r .chain.valid)" "true"
echo "      chain length $(echo "$BODY" | jq -r .chain.length), head $(echo "$BODY" | jq -r .chain.headHash | cut -c1-16)…"
THIS_MONTH=$(TZ=Asia/Singapore date +%Y-%m)
call POST "/reconciliations/month-end?period=$THIS_MONTH"
check "month-end for $THIS_MONTH (provisional, month not over)" "$(echo "$BODY" | jq -r '.status + " " + (.provisional|tostring)')" "CLEAN true"
call GET /admin/integrity-hold "" "" "X-Admin-Key: $ADMIN_KEY"
check "no integrity hold" "$(echo "$BODY" | jq -r .held)" "false"

echo
echo "passed=$pass failed=$fail"
exit "$fail"
