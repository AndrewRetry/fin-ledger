# Internal Ledger

[![ci](https://github.com/AndrewRetry/fin-ledger/actions/workflows/ci.yaml/badge.svg)](https://github.com/AndrewRetry/fin-ledger/actions/workflows/ci.yaml)
![Java 25](https://img.shields.io/badge/Java-25-blue)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f)
![PostgreSQL 18](https://img.shields.io/badge/PostgreSQL-18-336791)

A double-entry ledger service that keeps money exact. It records transfers atomically, answers balance and
history queries, and stays correct when requests are retried, run at the same time, reversed, or cross currencies.
Every transaction is hash-chained to the one before it, so history can't be edited quietly. Integrity checks
stop all writes the moment the numbers stop adding up.

It is built as the internal book of record a digital-asset custodian keeps beside its custody platform: the
platform knows *where* the coins are, this ledger knows *whose* they are.

---

## Contents

- [Quick start](#quick-start)
- [Try it](#try-it)
- [What it does](#what-it-does)
- [How a transfer works](#how-a-transfer-works)
- [API at a glance](#api-at-a-glance)
- [Requirements → tests](#requirements--tests)
- [Where this fits in a custody stack](#where-this-fits-in-a-custody-stack)
- [Key decisions](#key-decisions)
- [Assumptions](#assumptions)
- [Production gaps](#production-gaps)
- [Project layout](#project-layout)
- [AI usage](#ai-usage)

---

## Quick start

You need **Docker**. To run the tests locally you also need **Java 25**; to run the demo script, `curl` and `jq`.

```bash
docker compose up --build          # API on http://localhost:8080 — opening / takes you to Swagger UI
./backend/scripts/demo.sh          # walks through every requirement and prints PASS/FAIL per step
cd backend && ./mvnw verify        # the full test suite (Testcontainers starts PostgreSQL in Docker)
```

On startup the ledger creates four demo accounts with fixed ids, so you can try it straight away:

| Name  | Currency | Opening balance | Account id                             |
|-------|----------|-----------------|----------------------------------------|
| Alice | SGD      | 1,000.00        | `8398f93a-24c5-3661-8e9a-33d54273d824` |
| Bob   | SGD      | 250.00          | `9f75da44-22c8-31e4-9a7d-2bd07518cc4a` |
| Carol | USD      | 500.00          | `f5da7969-bb24-307c-ad5f-82b42413fc67` |
| Kenji | JPY      | 50,000          | `413efdce-9786-318b-be29-69819a82091a` |

To start again from an empty ledger: `docker compose down -v`. More ready-made requests are in
[`backend/docs/api-examples.http`](backend/docs/api-examples.http) (IntelliJ HTTP client or VS Code REST Client).

## Try it

Amounts are **integers in the currency's smallest unit**: `2500` is SGD 25.00, `50000` is JPY 50,000.

**Send SGD 25.00 from Alice to Bob.** Every money-moving request carries an `Idempotency-Key`; send the same
request again with the same key and you get the original transaction back, not a second transfer.

```bash
curl -s -X POST localhost:8080/transactions \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: lunch-2026-10-15' \
  -d '{"sourceAccountId":"8398f93a-24c5-3661-8e9a-33d54273d824",
       "destinationAccountId":"9f75da44-22c8-31e4-9a7d-2bd07518cc4a",
       "amount":2500, "description":"Lunch split"}'
```

The response is the stored transaction: its id, `"status": "POSTED"`, both postings with the balance each
account had afterwards, and the `txHash` that links it into the chain.

**Check Alice's balance:**

```bash
curl -s localhost:8080/accounts/8398f93a-24c5-3661-8e9a-33d54273d824
# { "name": "Alice", "currency": "SGD", "balance": { "amount": 97500, "currency": "SGD", "display": "975.00" }, ... }
```

**See her history, oldest first, with a running balance:**

```bash
curl -s 'localhost:8080/accounts/8398f93a-24c5-3661-8e9a-33d54273d824/transactions?page=0&size=50'
```

When something is refused, the answer says why and what to do, as an
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem with a stable `code` to branch on:

```json
{ "status": 422, "code": "INSUFFICIENT_FUNDS", "title": "Insufficient funds",
  "detail": "Account 9f75da44-… does not hold enough money for this amount.", "requestId": "…" }
```

## What it does

- **Atomic, double-entry transfers.** Every movement is written as where the money left (DEBIT) and where it
  arrived (CREDIT), for the same amount, in one database transaction. Either both sides happen or neither does.
- **No accidental double payments.** `Idempotency-Key` is required on every money-moving request. Same key and
  same body: the original result is replayed (`Idempotent-Replayed: true`). Same key and a different body: 422.
- **Reversals, never edits.** A mistake is corrected by posting its exact mirror image. A transaction can be
  reversed once; a reversal can't be reversed; if the money was already spent, the reversal is refused.
- **Multiple currencies.** A transfer from an SGD account to a USD account goes through the house's FX wallets:
  the sender pays the house in SGD, the house pays the recipient in USD, at the newest stored rate, rounded down.
- **Safe under load.** Simultaneous transfers on the same account queue on row locks instead of overwriting each
  other, and transfers in opposite directions can't deadlock.
- **Proves its own totals.** `POST /integrity/checks` re-adds everything from scratch: debits = credits, every
  balance = its postings, money in wallets = money deposited, and every link of the hash chain. It also runs
  every 10 minutes.
- **Fails closed.** If any check finds a discrepancy, writes stop (503) until an admin releases the hold. Reads
  keep working and carry an `X-Ledger-Integrity: HOLD` header.
- **Month-end reconciliation.** For every account, last month's closing + this month's credits − debits must land
  exactly on the recorded closing balance. A clean close freezes a snapshot, so later edits are caught.
- **Outside movements booked once.** A deposit or withdrawal can carry the outside system's id (`externalRef`,
  e.g. a Fireblocks transaction id); the same id is refused a second time, even under a new idempotency key.

## How a transfer works

Every money-moving request follows the same seven steps inside one database transaction. The order is
deliberate, and each step's reason is written next to it in
[`LedgerCommandService`](backend/src/main/java/com/andrewretry/ledger/journal/LedgerCommandService.java):

1. **Validate** with plain reads: the accounts exist, the currencies fit, the amount is positive.
2. **Lock every account involved** in one query, in id order, so two transfers can never wait on each other in a cycle.
3. **Check the idempotency key.** A retry of a request that already succeeded is replayed here, before any
   business rule could judge it again.
4. **Apply the postings.** A balance that would go below zero rejects the whole request, and nothing is written.
5. **Lock the chain head** (one row, always last). If the integrity hold is on, stop with 503.
6. **Write** the transaction, its postings and its hash, timestamped by the database clock.
7. **Commit.** Database triggers re-check every rule — balanced, at least two postings, chain linked — as a
   last line of defence against bugs in the Java code.

## API at a glance

Full, clickable documentation is at **http://localhost:8080/swagger-ui.html** while the app runs.

| Method | Path | What it does |
|---|---|---|
| `POST` | `/accounts` | Open a customer account in one currency |
| `GET` | `/accounts` | All accounts, oldest first, paged |
| `GET` | `/accounts/{id}` | Balance and details |
| `GET` | `/accounts/{id}/transactions` | History, oldest first, with the balance after each entry |
| `POST` | `/transactions` | Transfer between two accounts (FX when their currencies differ) |
| `GET` | `/transactions/{id}` | One transaction, and its reversal if it has one |
| `POST` | `/transactions/{id}/reversals` | Reverse a transaction |
| `POST` | `/deposits` · `/withdrawals` | Money in from, or out to, the outside world |
| `GET` | `/fx/rates` | The exchange rate in use for every currency pair |
| `GET` | `/ledger/head` | Newest link of the hash chain, to record outside the system |
| `POST` | `/integrity/checks` | Run every integrity check now |
| `POST` | `/reconciliations/month-end?period=2026-10` | Reconcile one month |
| `GET` | `/admin/integrity-hold` | Is the integrity hold on, and why (needs `X-Admin-Key`) |
| `POST` | `/admin/integrity-hold/release` | Release the hold, with a reason (needs `X-Admin-Key`) |
| `POST` | `/admin/fx-rates` | Add an exchange rate; it is used from the next transfer (needs `X-Admin-Key`) |

## Requirements → tests

Every requirement of the brief is proved by an integration test against a real PostgreSQL.

| Requirement | Proved by |
|---|---|
| `POST /transactions` with source, destination, amount | `TransferApiIT` |
| Recorded atomically (both sides or neither) | `TransferApiIT`, `SchemaGuardrailsIT` |
| Returns the transaction id and a confirmation | `TransferApiIT` |
| `GET /accounts/{id}` returns the current balance | `AccountApiIT`, `TransferApiIT` |
| `GET /accounts/{id}/transactions`, ordered by time | `HistoryApiIT` |
| Source decreases and destination increases by the same amount | `TransferApiIT` |
| Insufficient balance is rejected | `TransferApiIT`, `SchemaGuardrailsIT` |
| Reversal / correction transactions | `ReversalApiIT` |
| Repeated submission doesn't move money again | `IdempotencyApiIT`, `IdempotencyRaceIT` |
| Total money matches the expected sum; discrepancies are reported | `IntegrityIT`, `TamperDetectionIT` |
| Different currencies via exchange rates | `FxTransferApiIT`, `FxConversionTest` |
| Simultaneous transfers to one account lose no updates | `ConcurrencyIT` |
| Month-end reconciliation against history | `MonthEndIT`, `TamperDetectionIT` |
| *Beyond the brief:* one outside deposit can't be booked twice | `DepositWithdrawalApiIT` |
| *Beyond the brief:* random workloads keep every balance exact | `RandomizedInvariantIT` |

`ConcurrencyIT` fires 1,000 transfers from 50 threads at an account that can afford exactly 500 of them: exactly
500 succeed and the balance ends at zero. `TamperDetectionIT` edits history directly in the database, as only a
superuser could, and shows each edit being caught.

## Where this fits in a custody stack

A custody platform such as Fireblocks holds the keys and moves coins on-chain; this ledger is the book of record
for who owns what. An incoming transfer becomes a deposit whose `externalRef` is the platform's transaction id,
so a webhook delivered twice can never credit twice. The integration itself is designed (ADR-15) but not built.

## Key decisions

The full reasoning, with the options considered, is in
[`backend/docs/DESIGN_DECISIONS.md`](backend/docs/DESIGN_DECISIONS.md).

- **One balance rule for every account** — credit is money in, debit is money out. A FUNDING account per currency
  stands for the outside world, so every currency's balances sum to exactly zero (ADR-01, ADR-02).
- **Integer minor units**, never floating point. Decimals appear only in FX maths, rounded down on purpose (ADR-03).
- **The database is the last line of defence.** Triggers refuse unbalanced transactions, negative balances and any
  edit of history, whatever the Java code does (ADR-04).
- **Row locks in a fixed order, no retry loop.** A lock that times out returns 503 `TRY_AGAIN`; the client retries
  with the same idempotency key, which is always safe (ADR-05).
- **The idempotency key lives on the transaction row** under a unique constraint, so it never expires and a replay
  is rebuilt from what was stored (ADR-06).
- **A hash chain instead of a blockchain.** Tamper-evident and fully testable, without the cost of putting one
  operator's internal books on a public chain (ADR-10).

## Assumptions

1. An account holds one currency; a customer with SGD and USD has two accounts.
2. Amounts are integers in each currency's smallest unit (ISO 4217): SGD, USD, EUR have 2 decimals, JPY has 0.
3. Customer wallets and the house's FX wallets never go below zero.
4. Money enters and leaves only through deposits and withdrawals, against one FUNDING account per currency.
5. An `externalRef` is unique for the life of the ledger.
6. The server sets every timestamp from the database clock; clients can't backdate.
7. FX uses the newest stored rate for the pair, rounded down. Demo rates are seeded; an admin can add rates.
8. Reversals are full, at the original amounts and rate, at most once, and fail if the money was spent.
9. Idempotency keys are created by the client and unique for the life of the ledger.
10. Months follow the `Asia/Singapore` calendar; data is stored in UTC. A month still in progress gives a
    provisional report.
11. A discrepancy stops all money movement until an operator releases the hold.
12. This is an internal service behind a gateway: normal endpoints are open, admin endpoints need a key.
13. One instance was tested. All coordination is in database locks, so several instances should be safe, but
    that is untested.

## Production gaps

Deliberately out of scope for this version:

- The custody integration itself: webhook endpoint with signature checks, two-phase withdrawals, reconciling
  against vault balances (designed in ADR-15).
- Authentication and per-client key scoping; rate limiting.
- Live FX rates and a maximum rate age.
- Two-phase (pending → posted) transfers and partial reversals.
- Sharding hot accounts: the FUNDING and FX wallets, and the single chain head, serialise writes.
- Merkle-batched blocks and publishing the chain head to an outside anchor.
- Outbox events, partitioning and archiving, high availability and multi-region.
- Reconciliation against bank, custodian or on-chain balances.
- Assets with 8–18 decimals (`numeric(38,0)` / `BigInteger` instead of `bigint`).
- PII handling and data retention.

## Project layout

```
docker-compose.yml          PostgreSQL 18 + the app, with demo data
backend/
├── src/main/java/com/andrewretry/ledger/
│   ├── account/            accounts, balances, history
│   ├── journal/            transactions, postings, hash chain, the command pipeline
│   ├── fx/                 exchange rates and conversion
│   ├── integrity/          integrity checks, chain verifier, hold, month-end
│   ├── seed/               startup data (FX liquidity, demo accounts)
│   ├── common/ config/ web/  money, errors, configuration, filters
├── src/main/resources/
│   ├── db/migration/       schema, triggers and seed data (Flyway)
│   └── sql/checks/         the integrity and month-end queries
├── src/test/java/          unit and integration tests (JUnit 5, Testcontainers)
├── scripts/                demo.sh, tamper-demo.sh
└── docs/                   DESIGN_DECISIONS.md, api-examples.http
```

## AI usage

Planned with AI help and built with Claude Code:
[`PLAN.md`](backend/PLAN.md) and [`AGENTS.md`](backend/AGENTS.md) (both in the repo).

All design decisions are documented in the following document:
[`DESIGN_DECISIONS.md`](backend/docs/DESIGN_DECISIONS.md).
