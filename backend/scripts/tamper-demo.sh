#!/usr/bin/env bash
# Shows tamper detection and the fail-closed integrity hold on the LOCAL docker compose stack.
# It edits one posting directly in Postgres as the database superuser, bypassing every trigger,
# exactly like an attacker with database access would. Never run this against a real system.
# Usage: ./scripts/tamper-demo.sh   (after `docker compose up --build`)
set -euo pipefail
API="http://localhost:8080"; ADMIN="dev-admin"
ALICE="8398f93a-24c5-3661-8e9a-33d54273d824"; BOB="9f75da44-22c8-31e4-9a7d-2bd07518cc4a"
psql_db() { docker compose exec -T db psql -U ledger -d ledger -v ON_ERROR_STOP=1 -Atc "$1"; }

echo "1) Integrity before tampering:"
curl -sS -X POST "$API/integrity/checks" | jq '{status, integrityHold}'

echo "2) Tampering: add 5.00 to the newest customer CREDIT posting (triggers disabled for this session)"
psql_db "SET session_replication_role = replica;
         UPDATE postings SET amount = amount + 500
          WHERE id = (SELECT p.id FROM postings p
                        JOIN accounts a ON a.id = p.account_id
                        JOIN journal_transactions t ON t.id = p.transaction_id
                       WHERE a.kind = 'CUSTOMER' AND p.direction = 'CREDIT'
                       ORDER BY t.chain_index DESC LIMIT 1);"

echo "3) Integrity after tampering (expect DISCREPANCIES and integrityHold=true):"
curl -sS -X POST "$API/integrity/checks" \
  | jq '{status, integrityHold, codes: ([.discrepancies[].checkCode] | unique)}'

echo "4) Writes are now refused (expect 503 LEDGER_INTEGRITY_HOLD):"
curl -sS -X POST "$API/transactions" -H "Content-Type: application/json" \
  -H "Idempotency-Key: tamper-$(date +%s)" --data "{\"sourceAccountId\":\"$ALICE\",\"destinationAccountId\":\"$BOB\",\"amount\":1}" \
  | jq '{status, code}'

echo "5) Reads still work and are flagged:"
curl -sS -D - -o /dev/null "$API/accounts/$ALICE" | grep -i '^x-ledger-integrity'

echo "6) Admin view of the hold:"
curl -sS "$API/admin/integrity-hold" -H "X-Admin-Key: $ADMIN" | jq .
echo "To reset the demo: docker compose down -v && docker compose up --build"
