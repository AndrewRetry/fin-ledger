# PLAN.md — Internal Ledger (build plan for coding agents)

Version 2.1 (core track) · 2026-10-14

This file is the single source of truth for **what** to build and **in what order**. `AGENTS.md` says **how** to work. `docs/DESIGN_DECISIONS.md` says **why**. If code and this plan disagree, stop and ask; do not silently pick one.

v2 is deliberately smaller than v1: one hash chain instead of Merkle blocks, no retry layer, one balance rule for every account, seeded FX rates, no API keys or rate limiting in the core. v2.1 adds external references: an outside system's id for a deposit or withdrawal (e.g. a Fireblocks transaction id), so one outside movement can never be booked twice. Every assignment requirement is covered (§2).

---

## 0. How to use this plan

**For the coding agent**

1. Read `AGENTS.md` fully, then this file fully, before writing code.
2. Work one phase at a time (§11). Each phase lists its scope, its commits, and its "done when" gate. Do not start the next phase until the gate passes with `./mvnw -B verify`.
3. These files are **provided, authoritative and already tested** — use them as they are:
   - `src/main/resources/db/migration/V1..V5__*.sql` — schema, triggers, seeds. Verified on PostgreSQL 16 by a reference simulation that ran the exact step order of §7.1: 101 checks passed (workload, hot-account and opposite-direction concurrency, idempotency race, external references, month-end, all 35 guardrail cases of Appendix B, 7 tamper simulations).
   - `src/main/resources/sql/checks/*.sql` — integrity and month-end queries (same verification).
   - `src/main/resources/application.yml`, `Dockerfile`, `docker-compose.yml`, `.github/workflows/ci.yml`, `scripts/*.sh`, `docs/api-examples.http`.
4. Hash outputs must match Appendix A byte for byte (computed by two independent implementations, Python and Java, which agree).
5. "Exact" means: match names, paths, JSON fields and error codes exactly — `scripts/demo.sh` and the tests depend on them.

**For the owner (the person reviewing each phase)**

- Per phase: paste the prompt (§11), read the diff, run the gate, then read that phase's ADRs in `docs/DESIGN_DECISIONS.md`.
- §11 gives a time per phase (about 4.5 h for P0–P6). If time runs short, cut depth inside a phase (fewer tests of the same kind), never a requirement. P7 is optional.

---

## 1. Locked decisions

| # | Topic | Decision |
|---|---|---|
| D1 | Stack | Java 25 (LTS), Spring Boot 4.1.x (Spring Framework 7, Hibernate 7, Jackson 3), Maven wrapper |
| D2 | Database | PostgreSQL 18 locally (SQL is portable to 16+, tested on 16). Flyway owns the schema |
| D3 | Data access | Spring Data JPA with four rules: `ddl-auto=validate`; journal entities are `@Immutable`; accounts and the chain head are locked with explicit `@Lock(PESSIMISTIC_WRITE)` queries and never loaded before that lock; integrity and month-end reads are native SQL files run with `NamedParameterJdbcTemplate` |
| D4 | Model | Double-entry journal: a transaction = ≥ 2 postings that balance **per currency**. Corrections only by REVERSAL transactions |
| D5 | Balance rule | One rule for every account: **CREDIT = money in, DEBIT = money out**, `balance = credits − debits`. CUSTOMER and FX_LIQUIDITY balances never go below 0; the FUNDING account (the outside world) is always ≤ 0. So each currency's balances sum to exactly 0 |
| D6 | Money | `long` minor units ("cents"); ISO 4217 decimals per currency (SGD/USD/EUR 2, JPY 0). No `double`/`float` anywhere. `BigDecimal` only for FX rates |
| D7 | Concurrency | One `@Transactional` (READ COMMITTED) per command. Account rows locked with `SELECT … FOR UPDATE` in ascending id order, then the single `ledger_state` row is locked **last**. No retry layer: a lock timeout returns 503 `TRY_AGAIN` and the client retries with the same idempotency key |
| D8 | Idempotency | `Idempotency-Key` header required on every money-moving POST. Stored **on the journal transaction** with a UNIQUE constraint plus a request fingerprint. Same key + same body → replay (201 + `Idempotent-Replayed: true`); same key + different body → 422. Rejected requests write nothing, so their key can be retried |
| D9 | FX | One currency per account. Cross-currency = 4 postings through house FX liquidity wallets. Latest rate row for the pair (seeded demo rates; admins add rows with `POST /admin/fx-rates`). Floor rounding. The rate row id is stored on the transaction |
| D10 | Basic blockchain | Inline hash chain: each transaction stores `prev_hash` and `tx_hash = SHA-256(canonical(chain index, prev hash, fields, postings))`. `ledger_state` holds the head. A verifier recomputes every link |
| D11 | Integrity | 4 SQL checks + money supply + chain verification, on demand and every 10 min. Any discrepancy turns on the **integrity hold**: writes get 503 `LEDGER_INTEGRITY_HOLD`, reads continue with header `X-Ledger-Integrity: HOLD`, an admin releases it with a reason |
| D12 | Month-end | Per account: opening + month credits − month debits = recorded closing; opening = last month's frozen closing. Closes on `Asia/Singapore` calendar months; timestamps stored in UTC. A month that hasn't ended gives a provisional report with no snapshot |
| D13 | Access | Writes are open (internal service behind a gateway; documented). `/admin/**` needs `X-Admin-Key` |
| D14 | Testing | JUnit 5 + AssertJ + Testcontainers 2 (`postgres:18-alpine`). Concurrency tests use virtual threads behind a start latch |
| D15 | Usability | Paths match the assignment (`POST /transactions`, `GET /accounts/{id}`, `GET /accounts/{id}/transactions`). `docker compose up` seeds demo accounts with fixed ids; Swagger UI at `/swagger-ui.html` (`/` redirects there); `scripts/demo.sh` walks every requirement |
| D16 | External references | Deposits and withdrawals may carry `externalRef`: the outside world's id for that movement (a Fireblocks transaction id, a bank reference). Stored on the transaction under a UNIQUE constraint and covered by the hash, so one outside movement can never be booked twice — not even under a different idempotency key → 409 `EXTERNAL_REF_ALREADY_USED` |

---

## 2. Requirement traceability (fill the last column as tests land)

| Assignment line | Where it is built | Proved by |
|---|---|---|
| POST `/transactions` with source, destination, amount | `TransactionController`, `LedgerCommandService.transfer` | `TransferApiIT` |
| Recorded atomically (both or neither) | one `@Transactional`; deferred balance trigger | `TransferApiIT`, `SchemaGuardrailsIT` |
| Returns transaction ID and confirmation | `TransactionResponse` (`id`, `status: POSTED`, `txHash`) | `TransferApiIT` |
| GET `/accounts/{id}` returns current balance | `AccountController` | `AccountApiIT` |
| GET `/accounts/{id}/transactions` ordered by timestamp | `AccountHistoryQuery` (oldest first, by account version = posting time) | `HistoryApiIT` |
| Source decreases, destination increases by same amount | `Account.apply`, postings | `TransferApiIT` |
| Insufficient balance → reject | `INSUFFICIENT_FUNDS` + DB `CHECK` | `TransferApiIT`, `SchemaGuardrailsIT` |
| Reversal / correction transaction | `LedgerCommandService.reverse` + mirror trigger | `ReversalApiIT` |
| Repeated submission ≠ extra transfers | Idempotency (D8) | `IdempotencyApiIT`, `IdempotencyRaceIT` |
| (beyond the brief) One outside deposit can't be booked twice | External references (D16) | `DepositWithdrawalApiIT` |
| Total money matches expected sum; report discrepancies | `IntegrityService` (`MONEY_SUPPLY` + 4 checks + chain) | `IntegrityIT`, `TamperDetectionIT` |
| Different currencies via exchange rates | FX path (D9) | `FxTransferApiIT`, `FxConversionTest` |
| Simultaneous transfers to one account, no lost updates | ordered row locks (D7) | `ConcurrencyIT` |
| Month-end reconciliation against history | `MonthEndService` | `MonthEndIT`, `TamperDetectionIT` |
| Document assumptions | `docs/DESIGN_DECISIONS.md` §2, README | — |

---

## 3. Stack and dependencies

Generate the skeleton at start.spring.io — never hand-write the Boot 4 starter list from memory:

- Project **Maven**, Language **Java**, Spring Boot **latest 4.1.x**, Java **25**, Packaging Jar
- Group `com.andrewretry`, Artifact `ledger`, Package `com.andrewretry.ledger`
- Dependencies: **Spring Web**, **Spring Data JPA**, **Validation**, **Flyway Migration**, **PostgreSQL Driver**, **Spring Boot Actuator**, **Testcontainers**

Then add by hand (latest stable on Maven Central at build time):

| Artifact | Why |
|---|---|
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` (3.1.x line, for Boot 4.1) | Swagger UI |
| `org.flywaydb:flyway-database-postgresql` (if start.spring.io didn't add it) | Flyway's PostgreSQL support module |
| `org.testcontainers:testcontainers-postgresql` (test, Boot-managed version) | Testcontainers 2 artifact name |

Build settings in `pom.xml`: `<finalName>app</finalName>` (the Dockerfile copies `target/app.jar`). Surefire runs unit and integration tests together: `<includes><include>**/*Test.java</include><include>**/*IT.java</include></includes>`.

Not used on purpose: Lombok, MapStruct, H2, spring-retry, JavaMoney, Kafka.

---

## 4. Repository layout (exact)

```
ledger/
├── AGENTS.md  CLAUDE.md  PLAN.md  README.md
├── docs/  DESIGN_DECISIONS.md  api-examples.http
├── Dockerfile  docker-compose.yml  .github/workflows/ci.yml
├── scripts/  demo.sh  tamper-demo.sh
├── src/main/java/io/github/williamjacob/ledger/
│   ├── LedgerApplication.java
│   ├── common/      Money.java  Currencies.java  DbClock.java  Canonical.java  ConstraintNames.java  PageResponse.java
│   │   └── error/   ErrorCode.java  LedgerException.java  GlobalExceptionHandler.java
│   ├── config/      LedgerProperties.java  JacksonConfig.java  OpenApiConfig.java  JobsConfig.java  ClockConfig.java
│   ├── web/         RequestIdFilter.java  AdminKeyFilter.java  IntegrityHeaderFilter.java  RootController.java
│   ├── account/     Account.java  AccountKind.java  AccountCurrencyView.java  AccountRepository.java
│   │                AccountService.java  AccountHistoryQuery.java  AccountController.java  dto/…
│   ├── journal/     JournalTransaction.java  Posting.java  TransactionType.java  Direction.java  AppliedPosting.java
│   │                LedgerState.java  LedgerStateRepository.java  JournalTransactionRepository.java
│   │                TxHasher.java  RequestFingerprint.java  IdempotencyKeys.java  CommandResult.java
│   │                LedgerCommandService.java  TransactionQuery.java
│   │                TransactionController.java  MoneyMovementController.java  LedgerController.java  dto/…
│   ├── fx/          FxRate.java  FxRateRepository.java  FxConversion.java  FxRateService.java  FxController.java  dto/…
│   ├── integrity/   ChainVerifier.java  IntegrityService.java  IntegrityHoldService.java  MonthEndService.java
│   │                CheckCode.java  Discrepancy.java  ReconciliationRun.java  AccountPeriodBalance.java  …Repository.java
│   │                IntegrityController.java  ReconciliationController.java  AdminController.java  IntegrityJobs.java  dto/…
│   └── seed/        StartupSeeder.java
├── src/main/resources/
│   ├── application.yml                                   (provided)
│   ├── db/migration/V1__…sql … V5__system_accounts.sql   (provided)
│   └── sql/checks/*.sql                                  (provided)
└── src/test/java/io/github/williamjacob/ledger/   (mirrors main; plus support/)
    src/test/resources/application-test.yml
```

Rules: controllers call services, never repositories. `@Transactional` only on service classes.

---

## 5. Configuration

`application.yml` is provided. Bind it with one `@ConfigurationProperties(prefix = "ledger") @Validated record LedgerProperties(String adminKey, Close close, Integrity integrity, Jobs jobs, Seed seed)` with nested records `Close(ZoneId zone, String cron)`, `Integrity(Duration interval)`, `Jobs(boolean enabled)`, `Seed(long fxLiquidityMajorUnits, boolean demoData)`.

`src/test/resources/application-test.yml` (create in P0):

```yaml
ledger:
  admin-key: test-admin
  jobs:
    enabled: false        # tests call integrity / month-end directly
  seed:
    fx-liquidity-major-units: 1000000
    demo-data: false
```

All integration tests use `@ActiveProfiles("test")`. `ClockConfig` exposes a `java.time.Clock` bean (`Clock.systemUTC()`) used for "has this month ended?" decisions; tests may replace it. Timestamps **stored** in the ledger always come from the database (`DbClock`).

---

## 6. Domain model

### 6.1 Account kinds

| Kind | Balance | Meaning | Created by |
|---|---|---|---|
| `CUSTOMER` | ≥ 0 | A customer wallet | `POST /accounts` |
| `FX_LIQUIDITY` | ≥ 0 | The house's wallet per currency, used to convert FX | Migration V5; funded by `StartupSeeder` |
| `FUNDING` | ≤ 0 | The outside world per currency. Deposits come from it, withdrawals go back to it. Its balance = minus the net money deposited | Migration V5 |

Postings per command (every row is one posting; CREDIT = money in, DEBIT = money out):

| Command | Postings |
|---|---|
| Deposit X into wallet W | DEBIT `FUNDING-<ccy>` X · CREDIT W X |
| Withdraw X from wallet W | DEBIT W X · CREDIT `FUNDING-<ccy>` X |
| Transfer X from S to D (same currency) | DEBIT S X · CREDIT D X |
| FX transfer X from S (ccy A) to D (ccy B), converted Y | DEBIT S X · CREDIT `FX_LIQUIDITY-A` X · DEBIT `FX_LIQUIDITY-B` Y · CREDIT D Y |
| Reverse transaction T | every posting of T with DEBIT and CREDIT swapped, same accounts and amounts |

System accounts are found by `(kind, currency)` — a unique index guarantees one of each per currency.

### 6.2 Money (`common/Money.java`)

```java
public record Money(long amount, String currency, String display) {
    public static Money of(long amount, String currency, Currencies currencies) {
        return new Money(amount, currency,
                BigDecimal.valueOf(amount, currencies.minorUnits(currency)).toPlainString());
    }
}
```
- `Currencies` loads `currencies` once at startup (code → minor units); `minorUnits(code)`, `isSupported(code)`.
- 2500 SGD → `"25.00"`; 1500 JPY → `"1500"`; −100000 SGD → `"-1000.00"`.
- All `long` arithmetic uses `Math.addExact` / `Math.subtractExact`; overflow is `INVARIANT_VIOLATION` (500), never a wrap-around.

### 6.3 JPA entities (rules that prevent real bugs)

| Entity | Table | Mapping notes |
|---|---|---|
| `Account` | `accounts` | Mutable, but only through `apply`. Enum `kind` with `@Enumerated(STRING)`. `version` is a plain `long` — **not** `@Version`; `apply` increments it. No setters |
| `JournalTransaction` | `journal_transactions` | `@Immutable`. `@OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST) @OrderBy("accountId")` postings |
| `Posting` | `postings` | `@Immutable`. `@ManyToOne(fetch = LAZY) transaction`; `accountId` is a plain `UUID` column (no relation to `Account`, so it never loads accounts by accident) |
| `LedgerState` | `ledger_state` | Mutable single row (`id` is `short` = 1). Methods `advance(String newHash)`, `hold(String reason, Instant now)`, `release()` |
| `FxRate` | `fx_rates` | `@Immutable`. `rate` is `BigDecimal` with `@Column(precision = 24, scale = 12)` |
| `ReconciliationRun`, `Discrepancy`, `AccountPeriodBalance` | `reconciliation_runs`, `reconciliation_discrepancies`, `account_period_balances` | `@Immutable` |

Global rules:
- Ids are `UUID.randomUUID()`, assigned in constructors — no `@GeneratedValue`.
- `currencies.minor_units` is `smallint` → `short`. All hash and currency columns are `varchar`.
- If `ddl-auto=validate` complains, fix the **mapping** (`@Column`, `@JdbcTypeCode`), never the migration.
- **Stale-entity trap:** never load an `Account` or `LedgerState` entity in a transaction before locking it. Hibernate returns the already-loaded instance from a later locking query without refreshing it, so you would write stale numbers (a lost update). Pre-lock lookups use the projection `record AccountCurrencyView(UUID id, String currency, AccountKind kind)` via a JPQL constructor expression (`select new …AccountCurrencyView(a.id, a.currency, a.kind) from Account a where …`), which never creates managed entities.

### 6.4 `Account.apply` (the only place a balance changes)

```java
/** Applies one posting to this already-locked account and returns what the posting must record. */
public AppliedPosting apply(Direction direction, long amount) {
    long newBalance = direction == Direction.CREDIT
            ? Math.addExact(balance, amount)
            : Math.subtractExact(balance, amount);
    if (kind != AccountKind.FUNDING && newBalance < 0) {
        throw kind == AccountKind.FX_LIQUIDITY
                ? LedgerException.insufficientLiquidity(id)
                : LedgerException.insufficientFunds(id);
    }
    balance = newBalance;
    version = version + 1;
    return new AppliedPosting(id, currency, direction, amount, version, newBalance);
}
```

### 6.5 Locks (two repository methods, nothing else locks)

```java
// AccountRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select a from Account a where a.id in :ids order by a.id")
List<Account> lockAllByIdOrdered(@Param("ids") Collection<UUID> ids);

// LedgerStateRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from LedgerState s where s.id = 1")
LedgerState lockHead();
```
Lock order is always: **all accounts in one ordered call → then `lockHead()`**. Nothing locks `ledger_state` first and then accounts, so no cycle (deadlock) can form.

### 6.6 `DbClock`

`Instant now()` = `jdbcTemplate.queryForObject("select clock_timestamp()", OffsetDateTime.class).toInstant().truncatedTo(ChronoUnit.MICROS)`. `JdbcTemplate` joins the current JPA transaction (same connection). Called **after** `lockHead()`, so chain order and timestamp order agree.

---

## 7. Core algorithms

### 7.1 Command pipeline (every money-moving POST)

`Controller → LedgerCommandService` (`@Service`). Each public command method is `@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)` and returns `CommandResult(TransactionView view, boolean replayed)`. **The order of steps is part of the contract**; put a comment on each step in the code.

1. **Resolve and validate the request (no locks yet).** Use `AccountCurrencyView` only. Unknown account → `ACCOUNT_NOT_FOUND` (404). Then the command's own pre-checks (§7.2–7.5), e.g. `SAME_ACCOUNT`, kinds, `CURRENCY_MISMATCH`, FX rate and converted amount. Result: the full set of account ids to lock.
2. **Lock accounts** — one `lockAllByIdOrdered(ids)` call.
3. **Idempotency check** — `journalRepository.findByIdempotencyKey(key)`. Found: same `request_hash` → return `CommandResult(view, replayed = true)`; different → `IDEMPOTENCY_KEY_REUSED` (422). A duplicate request locks the same accounts, so it waits at step 2 until the first commits, then finds the row here.
4. **Apply** — post-lock checks (an `externalRef` already used by another transaction → `EXTERNAL_REF_ALREADY_USED` (409); reversal: `CANNOT_REVERSE_REVERSAL`, `ALREADY_REVERSED`), then `Account.apply` per posting (throws `INSUFFICIENT_FUNDS` / `INSUFFICIENT_LIQUIDITY`). These come **after** step 3 so a retry of a request that already succeeded is replayed, not re-judged. (A deposit or withdrawal with an `externalRef` always locks `FUNDING-<ccy>`, so two requests with the same reference in the same currency queue and the second sees the first.)
5. **Lock the chain head** — `ledgerStateRepository.lockHead()`. `integrityHold` → `LEDGER_INTEGRITY_HOLD` (503).
6. **Write** — `createdAt = dbClock.now()`; `chainIndex = head.chainLength + 1`; `prevHash = head.headHash`; `txHash = TxHasher.hash(...)` (§7.6); persist the `JournalTransaction` with its postings; `head.advance(txHash)`. Then `entityManager.flush()`, so constraint errors surface inside the method.
7. **Commit** — deferred triggers verify balance per currency, ≥ 2 postings, the reversal mirror, the chain link, and that every balance change has its posting. A trigger failure is a bug → `INVARIANT_VIOLATION` (500, logged at ERROR).

The view is built only from the transaction and its postings (never from live account rows), so a replay returns an identical body.

**Errors that escape the transaction** (mapped in `GlobalExceptionHandler`, using `ConstraintNames.of(e)` — finds `org.hibernate.exception.ConstraintViolationException#getConstraintName()` in the cause chain, else `PSQLException#getServerErrorMessage().getConstraint()`, else `""`; compare case-insensitively with `endsWith`):
- `DataIntegrityViolationException` on `uq_jt_idempotency_key` → 422 `IDEMPOTENCY_KEY_REUSED` (only reachable when two *different* bodies race with one key — identical bodies serialise on the same locks)
- on `uq_jt_reverses_id` → 409 `ALREADY_REVERSED`
- on `uq_jt_external_ref` → 409 `EXTERNAL_REF_ALREADY_USED` (two requests racing with one reference in *different* currencies, which share no lock)
- any other `DataIntegrityViolationException` → 500 `INVARIANT_VIOLATION`
- `TransientDataAccessException` (lock timeout 55P03, deadlock 40P01, serialization 40001) or `TransactionTimedOutException` → 503 `TRY_AGAIN` with `Retry-After: 1`

**Request fingerprint** (`RequestFingerprint`): lowercase hex SHA-256 of the canonical encoding (§7.6 `field`) of these strings, absent optional fields as `""`:
- `TRANSFER-V1, sourceAccountId, destinationAccountId, amount, currency, description`
- `DEPOSIT-V1, accountId, amount, description, externalRef` · `WITHDRAWAL-V1, accountId, amount, description, externalRef`
- `REVERSAL-V1, transactionId, reason`

Normalise before fingerprinting and storing: trim strings; blank → absent (`null` in the DB, `""` in the encoding).

**`Idempotency-Key` header** (`IdempotencyKeys.parse`): strip one pair of surrounding double quotes; then it must match `^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$`. Missing → 400 `IDEMPOTENCY_KEY_MISSING`; malformed → 400 `VALIDATION_FAILED`.

### 7.2 Transfer (`POST /transactions`)

Pre-checks (step 1), in order: both accounts exist; `sourceAccountId ≠ destinationAccountId` (`SAME_ACCOUNT`); both `CUSTOMER` (`ACCOUNT_KIND_NOT_ALLOWED`); `currency`, if sent, equals the source currency (`CURRENCY_MISMATCH`). Same currency → type `TRANSFER`, lock set = {source, destination}. Different currency → §7.3.

### 7.3 FX transfer (same endpoint, different currencies)

1. Rate = newest `fx_rates` row for (base = source ccy, quote = destination ccy): `order by created_at desc, id` limit 1. None → `FX_RATE_UNAVAILABLE` (422).
2. Converted amount (exact; Appendix A vectors):
   ```java
   long toMinor = BigDecimal.valueOf(fromMinor).multiply(rate)
           .scaleByPowerOfTen(currencies.minorUnits(dst) - currencies.minorUnits(src))
           .setScale(0, RoundingMode.FLOOR).longValueExact();
   ```
   `toMinor == 0` → `FX_AMOUNT_TOO_SMALL` (422). Floor = the remainder stays with the house.
3. Lock set = source, destination, `FX_LIQUIDITY-<src>`, `FX_LIQUIDITY-<dst>`.
4. Postings (type `FX_TRANSFER`, `fx_rate_id` set): DEBIT source X · CREDIT FXLIQ-src X · DEBIT FXLIQ-dst Y · CREDIT destination Y. FXLIQ-dst short of Y → `INSUFFICIENT_LIQUIDITY` (422).
5. The response's `fx` object: `{ "rateId", "base", "quote", "rate": rate.stripTrailingZeros().toPlainString(), "source" }`.

### 7.4 Deposit and withdrawal (`POST /deposits`, `POST /withdrawals`)

Body `{ "accountId", "amount", "description"?, "externalRef"? }`. `externalRef` must match `^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$` (e.g. `fb-tx-1001`): the outside system's id for this movement — in a custody setup, the Fireblocks transaction id of the incoming or outgoing transfer (ADR-15). Allowed kinds: `CUSTOMER`, `FX_LIQUIDITY` (else `ACCOUNT_KIND_NOT_ALLOWED`). Lock set = the wallet + `FUNDING-<ccy>`. Postings per §6.1. Withdrawal beyond the balance → `INSUFFICIENT_FUNDS`.

### 7.5 Reversal (`POST /transactions/{id}/reversals`)

Body `{ "reason" }` (required, 1–280 chars after trim; stored as the description).
1. Step 1: load the original with its postings (404 `TRANSACTION_NOT_FOUND`). Lock set = its posting accounts (postings are immutable, so reading them before locking is safe).
2. Steps 2–3 as usual.
3. Step 4: original is a `REVERSAL` → `CANNOT_REVERSE_REVERSAL` (422); already reversed (`existsByReversesId`) → `ALREADY_REVERSED` (409). Then mirror every posting (swap direction; same account, currency, amount). An account that would go negative → `INSUFFICIENT_FUNDS` (the recipient already spent it). FX transfers therefore reverse at their original rate.
4. Persist type `REVERSAL` with `reverses_id`. `GET /transactions/{original}` now shows `reversedById`.

### 7.6 Hashing spec (exact — Appendix A has the vectors)

**Canonical field encoding**: `field(s) = uint32 big-endian byte length of UTF-8(s) ‖ UTF-8(s)`. Numbers are base-10 strings; UUIDs lowercase `8-4-4-4-12`; timestamps are epoch **microseconds** as a base-10 string; absent values are `""`.

```
canonical = field("LEDGER-TX-V2") ‖ field(chainIndex) ‖ field(prevHash) ‖ field(id) ‖ field(type)
          ‖ field(createdAtMicros) ‖ field(idempotencyKey) ‖ field(externalRef or "") ‖ field(reversesId or "")
          ‖ field(fxRateId or "") ‖ field(description or "") ‖ field(postingCount)
          ‖ for each posting sorted by accountId (string order):
              field(accountId) ‖ field(currency) ‖ field(direction) ‖ field(amount)
              ‖ field(accountVersion) ‖ field(balanceAfter)
tx_hash   = lowercase hex( SHA-256( canonical ) )
```
The first transaction has chain index 1 and `prevHash` = 64 × `"0"`.

```java
public final class Canonical {                       // common/Canonical.java
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    public Canonical field(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
        out.writeBytes(b);
        return this;
    }
    public Canonical field(long n) { return field(Long.toString(n)); }
    public byte[] bytes() { return out.toByteArray(); }
    public static long micros(Instant t) { return ChronoUnit.MICROS.between(Instant.EPOCH, t); }
}
```

### 7.7 Chain verifier (`ChainVerifier.verify()` → `List<Discrepancy>`)

Read-only, in the caller's transaction. Page through `journal_transactions` by `chain_index` (pages of 500) with their postings. Keep `expectedIndex = 1`, `expectedPrev = 64 zeros`:
- `chainIndex ≠ expectedIndex` or `prevHash ≠ expectedPrev` → `CHAIN_LINK_BROKEN` (with `transaction_id`)
- recompute `tx_hash` from the row and its postings (§7.6); ≠ stored → `CHAIN_TX_HASH_MISMATCH` (with `transaction_id`)
- then `expectedIndex = chainIndex + 1`, `expectedPrev = stored tx_hash`

At the end, `ledger_state` must equal (last index, last stored hash) → else `CHAIN_HEAD_MISMATCH`. (Deleting the newest transaction is caught here; recomputing every hash after an edit is not — that is why `GET /ledger/head` exists: an auditor records the head outside the system.)

### 7.8 Integrity checks and the hold

`IntegrityService.run()`:
1. In one `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` method — one snapshot, one consistent answer — run:

   | Check code | Source | Row meaning |
   |---|---|---|
   | `TRIAL_BALANCE` | `sql/checks/trial_balance.sql` | one row per discrepancy |
   | `TRANSACTION_BALANCE` | `sql/checks/transaction_balance.sql` | one row per discrepancy |
   | `ACCOUNT_BALANCE` | `sql/checks/account_balances.sql` | one row per discrepancy |
   | `MONEY_SUPPLY` | `sql/checks/money_supply.sql` | **one row per currency**; report every row as a total, and a row with `expected ≠ actual` is a discrepancy |
   | `CHAIN_*` | `ChainVerifier` | |

   All check files return columns `account_id, transaction_id, currency, expected, actual, detail`. Load them from the classpath once at startup.
2. In a second transaction (a separate bean or `TransactionTemplate` with `REQUIRES_NEW`), insert one `reconciliation_runs` row (kind `INTEGRITY`, status `CLEAN` when no discrepancies) and its discrepancies.
3. Any discrepancy → `IntegrityHoldService.hold("integrity run <runId> found <n> discrepancies")`.

`IntegrityHoldService`:
- `hold(reason)`: `lockHead()`; if not held → `head.hold(reason, dbClock.now())`; log at ERROR. Idempotent.
- `release(reason)`: `lockHead()`; `head.release()`; log at WARN with the reason. The next scheduled check re-applies the hold if data is still broken.
- `status()`: plain read of `integrity_hold, hold_reason, hold_since` (used by the header filter, the admin endpoint and the health indicator).

`IntegrityHealthIndicator` reports DOWN with the reason while held (liveness stays UP).

### 7.9 Month-end (`MonthEndService.close(YearMonth period)`)

1. Zone = `ledger.close.zone`. `periodStart` = the 1st at 00:00 in that zone, `periodEnd` = next month's 1st at 00:00, both as instants. `periodStart > clock.instant()` → 400 `VALIDATION_FAILED` ("period has not started"). `periodEnd > clock.instant()` → **provisional**: use `periodEnd = clock.instant()`.
2. In a REPEATABLE READ read-only transaction run `sql/checks/month_end.sql` with `periodStart`, `periodEnd` (as `OffsetDateTime` UTC), `period` (first day, `LocalDate`), `previousPeriod` (first day of the previous month).
3. Per row:
   - `expected_closing ≠ actual_closing` → `MONTH_END_CLOSING_MISMATCH`
   - `previous_snapshot_closing` not null and ≠ `opening_balance` → `MONTH_END_OPENING_MISMATCH`
   - `existing_snapshot_closing` not null and ≠ `actual_closing` → `MONTH_END_SNAPSHOT_MISMATCH`
4. In a new transaction insert the run (kind `MONTH_END`, `period`, `provisional`) and discrepancies. If clean, **not** provisional, and no snapshot rows exist for that period yet → insert one `account_period_balances` row per account (opening, debits, credits, closing = actual, last version). First clean final run wins; the database refuses snapshots from any other run.
5. Discrepancies → hold, as in §7.8.
6. `IntegrityJobs` runs `close(previous month)` on `ledger.close.cron` in `ledger.close.zone`, and `IntegrityService.run()` every `ledger.integrity.interval` — only when `ledger.jobs.enabled`.

### 7.10 Startup seeding (`StartupSeeder`, on `ApplicationReadyEvent`)

1. **FX liquidity** (when `ledger.seed.fx-liquidity-major-units > 0`): for each currency, deposit `major × 10^minorUnits` into its `FX_LIQUIDITY` account through `LedgerCommandService.deposit`, key `seed-fxliq-<CCY>`, description `FX liquidity seed`. On restart the key replays, so liquidity is funded exactly once.
2. **Demo data** (when `ledger.seed.demo-data`): accounts with fixed ids `UUID.nameUUIDFromBytes(("ledger-demo:" + name).getBytes(UTF_8))`, created only if missing, then deposits with key `seed-demo-<name>`:

   | Name | Currency | Deposit (minor units) | Fixed id |
   |---|---|---|---|
   | Alice | SGD | 100000 (1,000.00) | `8398f93a-24c5-3661-8e9a-33d54273d824` |
   | Bob | SGD | 25000 (250.00) | `9f75da44-22c8-31e4-9a7d-2bd07518cc4a` |
   | Carol | USD | 50000 (500.00) | `f5da7969-bb24-307c-ad5f-82b42413fc67` |
   | Kenji | JPY | 50000 (¥50,000) | `413efdce-9786-318b-be29-69819a82091a` |

   (`name` in the id is lower-case: `ledger-demo:alice`.)

A `LedgerException` during seeding (e.g. an integrity hold) is logged at WARN and does not stop startup.

---

## 8. API contract (exact)

No path prefix. JSON only. Requests carry amounts as **integers in minor units** (`2500` = SGD 25.00). Responses show money as `{ "amount": 2500, "currency": "SGD", "display": "25.00" }`. A fractional amount (`10.5`), an amount as a string, or an unknown JSON field → 400. Timestamps are ISO-8601 UTC strings with microseconds.

Every response carries `X-Request-Id` (echoed or generated). While a hold is active every response also carries `X-Ledger-Integrity: HOLD`.

### 8.1 Endpoints

| Method | Path | Needs | Success | Notes |
|---|---|---|---|---|
| GET | `/` | — | 302 → `/swagger-ui.html` | |
| POST | `/accounts` | — | 201 `AccountResponse` | `{ "name" (1–100), "currency" }` → a `CUSTOMER` account. Unknown currency → 422 `CURRENCY_NOT_SUPPORTED` |
| GET | `/accounts` | — | 200 `PageResponse<AccountResponse>` | `?page=0&size=50` (size 1–200); ordered by `created_at`, then id |
| GET | `/accounts/{id}` | — | 200 `AccountResponse` | |
| GET | `/accounts/{id}/transactions` | — | 200 `HistoryPage` | `?page=0&size=50` (size 1–200); **oldest first** |
| POST | `/deposits` | Idempotency-Key | 201 `TransactionResponse` | `{ "accountId", "amount", "description"?, "externalRef"? }` |
| POST | `/withdrawals` | Idempotency-Key | 201 `TransactionResponse` | same body |
| POST | `/transactions` | Idempotency-Key | 201 `TransactionResponse` | `{ "sourceAccountId", "destinationAccountId", "amount", "currency"?, "description"? }`; FX when currencies differ |
| GET | `/transactions/{id}` | — | 200 `TransactionResponse` + `reversedById` | |
| POST | `/transactions/{id}/reversals` | Idempotency-Key | 201 `TransactionResponse` | `{ "reason" }` |
| GET | `/fx/rates` | — | 200 `[FxRateResponse]` | newest rate per pair, ordered by base, quote |
| GET | `/ledger/head` | — | 200 `{ "chainLength", "headHash" }` | |
| POST | `/integrity/checks` | — | 200 `IntegrityReport` | runs everything now |
| GET | `/integrity/runs/{id}` | — | 200 `IntegrityReport` | stored run; `moneySupply`, `chain` and `accounts` are null |
| POST | `/reconciliations/month-end?period=YYYY-MM` | — | 200 `IntegrityReport` (kind `MONTH_END`) | missing or malformed `period` → 400 |
| GET | `/admin/integrity-hold` | X-Admin-Key | 200 `{ "held", "reason", "since" }` | |
| POST | `/admin/integrity-hold/release` | X-Admin-Key | 200 same shape | `{ "reason" }` (1–500) |
| POST | `/admin/fx-rates` | X-Admin-Key | 201 `FxRateResponse` | `{ "base", "quote", "rate": "0.7812" }` (rate as a string, > 0, ≤ 12 decimals) → source `MANUAL` |

Common validation: `amount` required, `1 ≤ amount ≤ 1_000_000_000_000`; ids must be UUIDs; `description` ≤ 280 chars.

Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`. `OpenApiConfig` documents the `Idempotency-Key` and `X-Admin-Key` headers and adds one example body per POST.

### 8.2 Response shapes

`AccountResponse`
```json
{ "id": "8398f93a-24c5-3661-8e9a-33d54273d824", "name": "Alice", "kind": "CUSTOMER", "currency": "SGD",
  "balance": { "amount": 97500, "currency": "SGD", "display": "975.00" },
  "version": 2, "createdAt": "2026-10-14T03:00:00.123456Z" }
```

`TransactionResponse`
```json
{ "id": "…", "chainIndex": 42, "type": "TRANSFER", "status": "POSTED", "idempotencyKey": "demo-key-1", "externalRef": null,
  "createdAt": "2026-10-14T03:00:00.123456Z", "description": "Lunch split",
  "reversesId": null, "fx": null,
  "postings": [
    { "accountId": "…", "direction": "DEBIT",
      "amount": { "amount": 2500, "currency": "SGD", "display": "25.00" },
      "balanceAfter": { "amount": 97500, "currency": "SGD", "display": "975.00" }, "accountVersion": 2 },
    { "accountId": "…", "direction": "CREDIT",
      "amount": { "amount": 2500, "currency": "SGD", "display": "25.00" },
      "balanceAfter": { "amount": 27500, "currency": "SGD", "display": "275.00" }, "accountVersion": 2 } ],
  "prevHash": "…", "txHash": "…" }
```
Postings are ordered by `accountId`. `GET /transactions/{id}` adds `"reversedById"` — deliberately **not** in POST responses, so replays stay byte-identical.

`FxRateResponse`: `{ "id": "…", "base": "SGD", "quote": "USD", "rate": "0.775", "source": "SEED", "createdAt": "…" }` (`rate` = `stripTrailingZeros().toPlainString()`).

`PageResponse<T>`: `{ "items": [...], "page": 0, "size": 50, "totalItems": 123, "totalPages": 3 }`.

`HistoryPage` = `PageResponse<HistoryItem>` plus `"accountId"` and `"currency"`. `HistoryItem`:
```json
{ "transactionId": "…", "type": "TRANSFER", "direction": "DEBIT",
  "amount": { … }, "balanceAfter": { … }, "accountVersion": 2,
  "createdAt": "…", "description": "Lunch split" }
```
Ordered by `accountVersion` ascending — the same as time order, because an account's postings are written under its row lock.

`IntegrityReport`
```json
{ "runId": "…", "kind": "INTEGRITY", "period": null, "provisional": false, "status": "CLEAN",
  "startedAt": "…", "finishedAt": "…", "checkedAccounts": 12, "checkedTransactions": 40,
  "moneySupply": [ { "currency": "SGD", "expected": { … }, "actual": { … }, "matches": true } ],
  "chain": { "length": 40, "headHash": "…", "valid": true },
  "accounts": null,
  "snapshotsWritten": 0,
  "checks": [ { "code": "TRIAL_BALANCE", "discrepancies": 0 }, … ],
  "discrepancies": [ { "checkCode": "…", "accountId": null, "transactionId": null, "currency": "SGD",
                       "expected": 0, "actual": 0, "detail": "…" } ],
  "integrityHold": false }
```
For `MONTH_END`: `period` is `"2026-10"`, `moneySupply` and `chain` are null, `accounts` lists one row per account `{ "accountId", "currency", "opening", "debits", "credits", "expectedClosing", "actualClosing", "matches" }` (money objects), and `checks` lists the three `MONTH_END_*` codes.

### 8.3 Errors (RFC 9457 `application/problem+json`)

Body: `{ "type": "https://ledger.example/errors/<code-lowercase-dashes>", "title", "status", "detail", "code", "instance", "requestId" }` plus `"errors": [{ "field", "message" }]` for validation. Never a stack trace. The `detail` tells the caller what to do (e.g. "Send amounts as integer minor units, e.g. 2500 for 25.00").

| HTTP | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation, malformed JSON, fractional amount, bad UUID, bad key format, bad or future period |
| 400 | `IDEMPOTENCY_KEY_MISSING` | money-moving POST without `Idempotency-Key` |
| 401 | `UNAUTHORIZED` | missing or wrong `X-Admin-Key` (or no admin key configured) |
| 404 | `ACCOUNT_NOT_FOUND`, `TRANSACTION_NOT_FOUND`, `RUN_NOT_FOUND` | |
| 409 | `ALREADY_REVERSED`, `EXTERNAL_REF_ALREADY_USED` | |
| 422 | `INSUFFICIENT_FUNDS`, `INSUFFICIENT_LIQUIDITY`, `SAME_ACCOUNT`, `ACCOUNT_KIND_NOT_ALLOWED`, `CURRENCY_MISMATCH`, `CURRENCY_NOT_SUPPORTED`, `FX_RATE_UNAVAILABLE`, `FX_AMOUNT_TOO_SMALL`, `IDEMPOTENCY_KEY_REUSED`, `CANNOT_REVERSE_REVERSAL` | |
| 500 | `INVARIANT_VIOLATION` | a database guardrail fired or arithmetic overflowed — always a bug; log at ERROR with the request id |
| 503 | `LEDGER_INTEGRITY_HOLD`, `TRY_AGAIN` | with `Retry-After: 1` on `TRY_AGAIN` |

`ErrorCode` is one enum holding the HTTP status and a default title. `LedgerException` carries an `ErrorCode`, a detail message and optional properties (e.g. `accountId`). `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`.

---

## 9. Cross-cutting pieces

| Piece | Behaviour |
|---|---|
| `RequestIdFilter` (first) | Reads `X-Request-Id` or generates a UUID; puts it in the MDC as `requestId`; echoes it |
| `AdminKeyFilter` | For `/admin/**`: requires `X-Admin-Key` equal to `ledger.admin-key` (`MessageDigest.isEqual`). Blank configured key → always 401 |
| `IntegrityHeaderFilter` | Adds `X-Ledger-Integrity: HOLD` while held |
| `JacksonConfig` | Unknown fields fail; floats are not accepted as ints; scalar coercion (string → number) is off — see `AGENTS.md` §4 |
| Logging | Default Boot console format plus `%X{requestId}`. One INFO line per committed command (type, id, chain index), one WARN per rejected command (code). Never log full bodies |
| Scheduling | `JobsConfig` (`@EnableScheduling`, `@ConditionalOnProperty(name = "ledger.jobs.enabled", havingValue = "true")`) |

---

## 10. Test plan (exact class names; all in `src/test/java/...`)

Shared support:
- `support/IntegrationTest`: `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("test")`, `@Import(TestcontainersConfig.class)`. `TestcontainersConfig` is a `@TestConfiguration(proxyBeanMethods = false)` with `@Bean @ServiceConnection PostgreSQLContainer postgres()` on `postgres:18-alpine` (class `org.testcontainers.postgresql.PostgreSQLContainer`). Port via `@Value("${local.server.port}")`.
- `support/Api`: wraps `RestClient`; adds a fresh `Idempotency-Key` to money-moving POSTs unless the test passes one; `admin()` variant adds `X-Admin-Key: test-admin`. Returns status, headers and body without throwing on 4xx/5xx.
- `support/Fixtures`: `customer(name, ccy)`, `funded(name, ccy, amountMinor)`.
- Tests never truncate or delete (the tables refuse it). Each test creates its own accounts. Assertions about the whole ledger (integrity CLEAN) are valid because every test writes only valid data.

| Class | Kind | Must prove |
|---|---|---|
| `MoneyTest` | unit | display for 2- and 0-decimal currencies and negatives; overflow throws |
| `FxConversionTest` | unit | every FX vector in Appendix A |
| `TxHasherTest`, `RequestFingerprintTest` | unit | every hash vector in Appendix A (incl. canonical length and prefix) |
| `IdempotencyKeysTest` | unit | quotes stripped; valid / invalid formats |
| `LedgerApplicationIT` | integration | context starts; Flyway applied V1–V5; 8 system accounts exist; FX liquidity seeded once (restart-safe: calling the seeder again changes nothing) |
| `SchemaGuardrailsIT` | integration (JdbcTemplate), **own context and container** (its fixtures carry fake hashes) | the 35 cases of Appendix B each fail with the stated fragment |
| `AccountApiIT` | integration | create / get / list / paging; 404; validation errors; unsupported currency → 422 |
| `TransferApiIT` | integration | happy path (balances, 2 postings, `txHash`, `chainIndex`); insufficient funds; same account; unknown account; currency mismatch; `amount: 10.5` → 400; `amount: "100"` → 400; unknown field → 400; missing key → 400 |
| `DepositWithdrawalApiIT` | integration | deposit; withdrawal; overdraw rejected; deposit into FX liquidity allowed; deposit into FUNDING → 422; `externalRef` stored and returned; same `externalRef` under a new key → 409 `EXTERNAL_REF_ALREADY_USED`; same key + same body → replay |
| `HistoryApiIT` | integration | oldest-first order; paging; `balanceAfter` per row |
| `IdempotencyApiIT` | integration | same key + body → 201 + `Idempotent-Replayed: true` + identical body; different body → 422; a rejected request's key works later after funding |
| `IdempotencyRaceIT` | concurrency | 20 identical requests released together → exactly 1 transaction, 19 replays with the same id, money moved once |
| `ReversalApiIT` | integration | reverse a transfer; second reversal → 409; reverse a reversal → 422; recipient spent the money → 422; replaying the reversal request → replay, not 409; FX reversal restores exact amounts in both currencies |
| `FxTransferApiIT` | integration | SGD → USD at the seeded 0.775 (10000 → 7750); rate added via `/admin/fx-rates` is used next; 4 postings; liquidity short → 422; too small → 422; admin endpoint without key → 401 |
| `ConcurrencyIT` | concurrency | **Hot account:** balance 50 000; 1 000 transfers of 100 from 50 virtual threads → exactly 500 succeed, 500 `INSUFFICIENT_FUNDS`, balance 0. **Opposite directions:** 500 transfers of 1 between A and B both ways at once → all succeed, balances unchanged. Integrity CLEAN afterwards |
| `IntegrityIT` | integration | after a mixed workload: run CLEAN; `moneySupply` matches per currency; chain valid and `/ledger/head` equals the last transaction; `GET /integrity/runs/{id}` returns it |
| `MonthEndIT` | integration, own context with a fixed `Clock` on the 5th of next month | closing this month is final and CLEAN and writes one snapshot per account; re-run CLEAN and writes none; closing next month is provisional and writes none; a month two ahead → 400 |
| `TamperDetectionIT` | integration, **own context and container** (unique `@TestPropertySource`), fixed `Clock` on the 5th of next month | as superuser with `SET session_replication_role = replica`: (1) raise a posting's amount → `TRIAL_BALANCE`, `TRANSACTION_BALANCE`, `ACCOUNT_BALANCE`, `CHAIN_TX_HASH_MISMATCH`; hold on; POST → 503; GET → 200 with `X-Ledger-Integrity: HOLD`; a replay still answers; admin release works. (2) edit only a description → only `CHAIN_TX_HASH_MISMATCH`. (3) after closing a month, edit a posting in it → month-end re-run reports `MONTH_END_CLOSING_MISMATCH` |
| `RandomizedInvariantIT` | integration | 3 fixed seeds × 150 random operations (deposit / withdraw / transfer / FX / reversal; expected rejections allowed) → integrity CLEAN and every balance equals an in-memory model |

Concurrency rules: start all tasks behind a `CountDownLatch`; `Executors.newVirtualThreadPerTaskExecutor()`; call `LedgerCommandService` directly (not HTTP); assert outcomes and invariants, never timing.

---

## 11. Phases

Gate for every phase: `./mvnw -B verify` green, CI green, no `TODO` in the phase's files, the phase's commits made, a 5-line summary posted.

Commit format: Conventional Commits, several per phase (`feat(journal): …`, `test(concurrency): …`, `docs(adr): …`).

**Prompt to paste for any phase** (replace `Pn`):

> Implement phase **Pn** from `PLAN.md` §11, following `AGENTS.md`. Use the sections the phase cites as the exact spec (names, paths, JSON fields, error codes). Write the listed tests first or alongside the code. Do not touch files outside the phase's scope except to fix a bug you must explain. Commit in the listed steps. Stop when the gate passes and give me: files changed, tests added, any deviation from the plan and why, and which `DESIGN_DECISIONS.md` sections I should read.

| Phase | Your time | Scope (cites) | Commits | Done when |
|---|---|---|---|---|
| **P0 Bootstrap** | 0.5 h | Generate the project (§3); add deps; copy the provided files; delete `application.properties`; `application-test.yml` (§5); `LedgerApplication`; `support/IntegrationTest` + `TestcontainersConfig`; `LedgerApplicationIT` (without the seeding assertions yet); `SchemaGuardrailsIT` with `RawPost` (Appendix B) | `chore: Spring Boot 4.1 skeleton` · `build: springdoc, testcontainers` · `test(schema): prove every database guardrail` | Both ITs green; `docker compose up --build` → `/actuator/health` UP |
| **P1 Accounts and plumbing** | 0.5 h | `common/*` (§6.2, §6.6, §7.6 `Canonical`, `ConstraintNames`), errors (§8.3), `LedgerProperties` (§5), `ClockConfig`, `JacksonConfig`, `Account` + repository + projection (§6.3), `AccountService`, `AccountController` (§8.1), `RequestIdFilter`, `RootController` | `feat(common): money, db clock, canonical encoding` · `feat(errors): problem details` · `feat(account): create, get, list` | `MoneyTest`, `AccountApiIT` green |
| **P2 Journal, chain, transfers** | 1 h | `JournalTransaction`, `Posting`, `LedgerState` + repositories, locks (§6.5), `Account.apply` (§6.4), `TxHasher` (§7.6), `RequestFingerprint`, `IdempotencyKeys`, `LedgerCommandService` full pipeline (§7.1) with deposit, withdrawal and same-currency transfer (§7.2, §7.4), `TransactionController`, `MoneyMovementController`, `GET /transactions/{id}`, history (§8.2), `LedgerController` (`/ledger/head`) | `feat(journal): entities, chain head, tx hash` · `feat(journal): deposit, withdrawal, transfer` · `feat(account): history` · `test(concurrency): hot account and opposite transfers` | `TxHasherTest`, `RequestFingerprintTest`, `IdempotencyKeysTest`, `TransferApiIT`, `DepositWithdrawalApiIT`, `HistoryApiIT`, `ConcurrencyIT` green |
| **P3 Idempotency and reversals** | 0.5 h | `Idempotent-Replayed` header, error mapping of escaping exceptions (§7.1), reversal (§7.5) | `feat(journal): reversals` · `test: idempotency and reversal` | `IdempotencyApiIT`, `IdempotencyRaceIT`, `ReversalApiIT` (non-FX cases) green |
| **P4 FX** | 0.5 h | `FxRate`, `FxRateService` (latest per pair), `FxConversion` (§7.3), FX path, `GET /fx/rates`, `POST /admin/fx-rates`, `AdminKeyFilter`, `StartupSeeder` step 1 (§7.10) | `feat(fx): rates and conversion` · `feat(fx): cross-currency transfers` · `feat(seed): fund FX liquidity` | `FxConversionTest`, `FxTransferApiIT`, FX case of `ReversalApiIT`, seeding assertions of `LedgerApplicationIT` green |
| **P5 Integrity and month-end** | 0.75 h | `ChainVerifier` (§7.7), `IntegrityService`, `IntegrityHoldService`, `IntegrityHeaderFilter`, health indicator (§7.8), `AdminController`, `IntegrityController`, `MonthEndService` (§7.9), `ReconciliationController`, `IntegrityJobs`, `JobsConfig` | `feat(integrity): checks, chain verifier, hold` · `feat(reconciliation): month-end` · `test: tamper detection` | `IntegrityIT`, `MonthEndIT`, `TamperDetectionIT`, `RandomizedInvariantIT` green |
| **P6 Pleasant to use, documented, reviewed** | 0.75 h | `StartupSeeder` step 2 (demo data), `OpenApiConfig` (headers + examples), `README.md` (Appendix C), fill the **Evidence** lines in `DESIGN_DECISIONS.md`, run `scripts/demo.sh` against `docker compose up` until all PASS, then a fresh-eyes review by a **separate** agent session using the checklist below; fix findings; tag `v1.0.0` | `feat(seed): demo accounts` · `docs: README, ADR evidence, API examples` · `fix: review findings` · `chore(release): v1.0.0` | `scripts/demo.sh` all PASS; checklist ticked; repo public |
| **P7 Optional** (only with time left, in this order) | — | (a) hosted demo (Render free + Neon free); (b) a Fireblocks webhook adapter per ADR-15, tested offline with a generated key pair: verify the signature on the raw body, turn a COMPLETED incoming transfer into a deposit with `externalRef` = the Fireblocks transaction id (a new dependency for JWS — ask first); (c) live ECB rates via Frankfurter every 6 h with a max-age check and the seeded rates as fallback; (d) `X-API-Key` on writes + per-client rate limiting with Bucket4j (ask first); (e) Merkle-batched blocks | one commit each | each with tests; README updated |

**P6 review checklist** (the reviewing agent answers each with evidence):

- [ ] Every row of §2 has a passing test named in the README table.
- [ ] No `double`, `float` or `BigDecimal` amount fields; `BigDecimal` only in FX code.
- [ ] No `Account` or `LedgerState` entity is loaded before it is locked (read `LedgerCommandService` top to bottom).
- [ ] Locks: one ordered account lock call, then `lockHead()`, in every command; nothing else locks.
- [ ] Post-lock checks (funds, `externalRef`, already reversed) run after the idempotency check.
- [ ] Every money-moving POST requires `Idempotency-Key`; replays return identical bodies.
- [ ] Every `ErrorCode` is reachable and documented.
- [ ] `docker compose up --build` on a clean clone works; README commands are copy-paste correct; demo ids in README match §7.10.
- [ ] `DESIGN_DECISIONS.md` has no placeholder left; every ADR has Evidence.

---

## 12. Running it

**Local**: `docker compose up --build` → API on `http://localhost:8080` (`/` opens Swagger UI). Compose sets `LEDGER_ADMIN_KEY=dev-admin` and `LEDGER_DEMO_DATA=true`. Reset: `docker compose down -v`.

**CI**: `.github/workflows/ci.yml` runs `./mvnw -B verify` on Java 25 (Testcontainers uses the runner's Docker).

**Hosted demo** (P7a only): Render free web service (Docker, region Singapore, health check `/actuator/health/liveness`) + Neon free Postgres in Singapore. Use Neon's **direct** connection string (host without `-pooler`), set `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` and `LEDGER_ADMIN_KEY` in Render. The free service sleeps after 15 idle minutes (~1 min cold start); keep Hikari `minimum-idle: 0` so Neon can scale to zero.

---

## 13. Out of scope (state these in the README as production gaps)

The Fireblocks integration itself — webhook endpoint with signature checks, two-phase withdrawals, vault-balance reconciliation (designed in ADR-15, not built) · authentication and per-client key scoping · rate limiting · live FX rates and rate staleness · two-phase (pending → posted) transfers · partial reversals · hot-account sharding (the funding and FX liquidity accounts, and the single chain head, serialise writes) · Merkle-batched blocks and external anchoring of the chain head · outbox events · partitioning and archiving · reconciliation against bank, custodian or on-chain balances · 8–18-decimal digital assets (`numeric(38,0)` / `BigInteger`) · HA and multi-region · PII and retention.

---

## Appendix A — Test vectors (Python and Java agree)

Sample data (all ids are `0192f0a0-0000-7000-8000-` + the suffix shown):
- Accounts: A `…00000000000a`, B `…00000000000b`, FXLIQ-SGD `…00000000000c`, FXLIQ-USD `…00000000000d`, C `…00000000000e`, FUNDING-SGD `…00000000000f`
- **tx1**: chain index 1, prevHash 64 × `0`, id `…000000000001`, type `TRANSFER`, createdAt `2026-10-14T03:00:00.123456Z`, key `demo-key-1`, no externalRef, no reversal, no FX, description `Lunch split`, postings (given B first on purpose — the encoder must sort by accountId):
  - B, SGD, CREDIT, 2500, version 1, balanceAfter 2500
  - A, SGD, DEBIT, 2500, version 3, balanceAfter 7500
- **tx2**: chain index 2, prevHash = tx1 hash, id `…000000000002`, type `REVERSAL`, createdAt `2026-10-14T03:00:01Z`, key `demo-key-2`, no externalRef, reversesId = tx1 id, no FX, description `Entered twice`, postings:
  - A, SGD, CREDIT, 2500, version 4, balanceAfter 10000
  - B, SGD, DEBIT, 2500, version 2, balanceAfter 0
- **tx3**: chain index 3, prevHash = tx2 hash, id `…000000000003`, type `FX_TRANSFER`, createdAt `2026-10-14T03:00:02.5Z`, key `demo-key-3`, no externalRef, no reversal, fxRateId `…0000000000f1`, no description, postings (given in this order):
  - C, USD, CREDIT, 7750, version 1, balanceAfter 7750
  - FXLIQ-USD, USD, DEBIT, 7750, version 2, balanceAfter 99992250
  - A, SGD, DEBIT, 10000, version 5, balanceAfter 0
  - FXLIQ-SGD, SGD, CREDIT, 10000, version 1, balanceAfter 10000
- **tx4**: chain index 4, prevHash = tx3 hash, id `…000000000004`, type `DEPOSIT`, createdAt `2026-10-14T03:00:03Z`, key `demo-key-4`, externalRef `fb-tx-1001`, no reversal, no FX, description `Fireblocks deposit`, postings:
  - FUNDING-SGD, SGD, DEBIT, 50000, version 7, balanceAfter -150000
  - A, SGD, CREDIT, 50000, version 6, balanceAfter 50000

| Vector | Expected (lowercase hex) |
|---|---|
| canonical bytes of tx1 | 362 bytes, starting `0000000c4c45444745522d54582d5632000000013100000040303030303030303030303030303030` |
| `tx_hash(tx1)` | `5fc81c8d533a218242da8ff6709fb74924c8fcba6f91dc6a713f5defb53194ae` |
| `tx_hash(tx2)` | `989d8af316373cf36472b1692966dcac1103885a6abc6be5ddd8cde877e8b815` |
| `tx_hash(tx3)` | `d9e3f0b83c2999e3c1984ba4ea9de3155fc2602bcea1f16c7077fda34f14c32d` |
| `tx_hash(tx4)` | `883c8817a5a05ec62999d501b93e5f1638adbf86f08e60ec934c619920cee9c1` |
| fingerprint `TRANSFER-V1, A, B, 2500, SGD, Lunch split` | `f6ee2bbbdf0fcf371903d4f2a3b64ae234e9d2f40441165a9526a9a939d786fe` |
| fingerprint `TRANSFER-V1, A, B, 2500, "", ""` | `a57fc881ac5552c340b3a3f013a006a5be0c334f96baec31c0d27d94ed20ba77` |
| fingerprint `DEPOSIT-V1, A, 10000, "", ""` | `76bf77d4faa8f850c20269faccc207c6a8207398a63868a141ac2a1a916fb791` |
| fingerprint `DEPOSIT-V1, A, 50000, Fireblocks deposit, fb-tx-1001` | `e5b245ac1c026084b46ffa0f5fda942b71c7deb6aff5f168169a5ed6042f0937` |
| fingerprint `REVERSAL-V1, tx1 id, Entered twice` | `1108edc5a6988ce5523cf4a8cc148a60cb798ee66086c9f127de1f5023752a82` |

FX conversion (`fromMinor`, rate, source decimals → destination decimals ⇒ result):

| From | Rate | Decimals | Result | Note |
|---|---|---|---|---|
| 10000 (SGD 100.00) | 0.775 | 2 → 2 | 7750 (USD 77.50) | seeded SGD→USD rate |
| 999 (USD 9.99) | 151.51515 | 2 → 0 | 1513 (JPY) | floor of 1513.636… |
| 1 (JPY 1) | 0.0066 | 0 → 2 | 0 | → `FX_AMOUNT_TOO_SMALL` |
| 12345 (EUR 123.45) | 1.4967742 | 2 → 2 | 18477 (SGD 184.77) | floor |
| 10000 (USD 100.00) | 151.234567 | 2 → 0 | 15123 (JPY) | floor of 15123.4567 |
| 250000 (JPY 250,000) | 0.0066118 | 0 → 2 | 165295 (USD 1,652.95) | exact |
| 12345 (SGD 123.45) | 0.68123 | 2 → 2 | 8409 (EUR 84.09) | floor of 8409.78… |

---

## Appendix B — Database guardrail cases for `SchemaGuardrailsIT`

Each case runs in its own transaction (`TransactionTemplate`) and must fail; assert that the exception's cause chain contains the fragment (in the Postgres message or the constraint name). "Commit" means the failure appears only at COMMIT (deferred trigger).

This class runs in its **own Spring context and container** (unique `@TestPropertySource`), because its fixtures are written with fake hashes and would make integrity checks in other tests fail. It needs only `JdbcTemplate` and `TransactionTemplate`, so it works in P0 before any service exists.

Test helper `RawPost.post(type, legs, options)` writes a transaction the way the service does, so one rule at a time can be broken: lock `ledger_state` FOR UPDATE; insert a `journal_transactions` row (chain index = length + 1, prev = head hash, random 64-hex `tx_hash`, random key, request hash 64 × `0`); for each leg `(account, currency, direction, amount)` insert a posting with version = account version + 1 and the new balance, then update the account; update `ledger_state` to the new index and hash. Options: `key`, `externalRef`, `reversesId`, `fxRateId`, `chainIndex`, `prevHash`, `versions`, `advanceHead=false`, `updateAccounts=false`.

Fixtures (committed once in `@BeforeAll`, with SQL + `RawPost`): customers A and B (SGD) and C (USD) inserted with `balance 0`; a DEPOSIT FUNDING-SGD DEBIT 100000 / A CREDIT 100000 (`externalRef = 'guard-ext-1'`); a TRANSFER A→B 2500 (`key = 'guard-t1'`); a REVERSAL of that transfer (case 16 targets the transfer, case 17 the reversal); a second TRANSFER A→B 1000 that stays unreversed (case 18).

| # | Attempt | Fails with |
|---|---|---|
| 1 | `UPDATE postings SET amount = amount + 1` | `append-only` |
| 2 | `DELETE FROM journal_transactions` | `append-only` |
| 3 | `TRUNCATE postings CASCADE` | `append-only` |
| 4 | `DELETE FROM accounts WHERE id = B` | `append-only` |
| 5 | `UPDATE accounts SET currency = 'USD' WHERE id = B` | `immutable` |
| 6 | `UPDATE accounts SET version = version - 1 WHERE id = A` | `cannot go down` |
| 7 | `UPDATE accounts SET balance = balance + 100 WHERE id = A` | `without a new version` |
| 8 | `UPDATE accounts SET balance = balance + 100, version = version + 1 WHERE id = A` | `without a matching posting` (commit) |
| 9 | `INSERT INTO accounts (id, name, kind, currency, balance) VALUES (…, 'Rich', 'CUSTOMER', 'SGD', 100)` | `must start empty` |
| 10 | RawPost TRANSFER: A DEBIT 100, B CREDIT 99 | `unbalanced` (commit) |
| 11 | RawPost DEPOSIT with one leg: B CREDIT 100 | `posting(s)` (commit) |
| 12 | RawPost TRANSFER: A with currency USD DEBIT 100, C CREDIT 100 | `fk_postings_account_currency` |
| 13 | RawPost TRANSFER: B DEBIT 10^15, A CREDIT 10^15 | `ck_accounts_non_negative` |
| 14 | `UPDATE accounts SET balance = 500, version = version + 1 WHERE id = FUNDING-SGD` | `ck_accounts_funding_non_positive` |
| 15 | RawPost TRANSFER A→B 1 with an existing idempotency key | `uq_jt_idempotency_key` |
| 16 | RawPost REVERSAL of an already-reversed transaction | `uq_jt_reverses_id` |
| 17 | RawPost REVERSAL whose `reversesId` is a reversal | `cannot be reversed` (commit) |
| 18 | RawPost REVERSAL of an unreversed transfer with legs that don't mirror it | `does not mirror` (commit) |
| 19 | RawPost TRANSFER with no legs | `posting(s)` (commit) |
| 20 | RawPost TRANSFER A→B 1 with `versions = [1, 1]` (A already has version 1) | `uq_postings_account_version` |
| 21 | RawPost FX_TRANSFER without `fxRateId` | `ck_jt_fx_link` |
| 22 | RawPost with `chainIndex = 1000000` | `not the next index` |
| 23 | RawPost with `prevHash = 64 × 'f'` | `does not link` |
| 24 | RawPost with `advanceHead = false` | `without advancing the chain head` (commit) |
| 25 | `UPDATE ledger_state SET chain_length = chain_length + 1, head_hash = 64 × 'a'` | `does not match the newest` (commit) |
| 26 | `UPDATE ledger_state SET chain_length = chain_length + 2` | `one transaction at a time` |
| 27 | RawPost with `updateAccounts = false` | `still at version` (commit) |
| 28 | `DELETE FROM ledger_state` | `append-only` |
| 29 | `UPDATE ledger_state SET integrity_hold = true` (no reason) | `ck_ledger_state_hold` |
| 30 | `UPDATE fx_rates SET rate = rate * 2` | `append-only` |
| 31 | insert a `reconciliation_runs` row (MONTH_END, period `2020-01-01`, status `DISCREPANCIES`, count 1), then an `account_period_balances` row for it | `clean, final month-end run` |
| 32 | insert a CLEAN final MONTH_END run for `2020-01-01`, then a snapshot with opening 0, debits 0, credits 5, closing 7 | `ck_apb_arithmetic` |
| 33 | `UPDATE currencies SET minor_units = 3` | `append-only` |
| 34 | RawPost DEPOSIT FUNDING-SGD DEBIT 1 / B CREDIT 1 with `externalRef = 'guard-ext-1'` (already used) | `uq_jt_external_ref` |
| 35 | RawPost TRANSFER A→B 1 with `externalRef = 'guard-ext-2'` | `ck_jt_external_ref_kind` |

After the cases: a valid `RawPost` TRANSFER A→B 1 still commits (the rejected attempts left nothing behind). Case 15 reuses `guard-t1`.

---

## Appendix C — README skeleton

```markdown
# Internal Ledger

Double-entry ledger service (Java 25, Spring Boot 4.1, PostgreSQL 18): atomic, idempotent transfers,
reversals, multi-currency through FX liquidity accounts, a hash-chained journal, integrity checks that
stop writes when something is wrong, and month-end reconciliation.

## Run it (one command)
docker compose up --build        # API on http://localhost:8080 — / opens Swagger UI
./scripts/demo.sh                # walks every requirement, prints PASS/FAIL (needs curl + jq)
./mvnw verify                    # all tests (needs Docker for Testcontainers)

Demo accounts are created on startup: Alice 8398f93a-24c5-3661-8e9a-33d54273d824 (SGD 1,000.00),
Bob 9f75da44-22c8-31e4-9a7d-2bd07518cc4a (SGD 250.00), Carol f5da7969-bb24-307c-ad5f-82b42413fc67 (USD 500.00),
Kenji 413efdce-9786-318b-be29-69819a82091a (JPY 50,000). Ready-made requests: docs/api-examples.http.

## Try it
<3 curl commands: transfer (with Idempotency-Key), balance, history>

## Requirements → tests
<table from PLAN.md §2 with the final test names>

## Where this fits in a custody stack
<3 lines from ADR-15: a custody platform such as Fireblocks holds the keys and moves coins on-chain; this
ledger is the book of record for who owns what; an incoming transfer becomes a deposit whose externalRef is
the platform's transaction id, so a repeated webhook can never credit twice>

## Key decisions (details in docs/DESIGN_DECISIONS.md)
<6 bullets>

## Assumptions
<numbered list from DESIGN_DECISIONS.md §2>

## Production gaps
<PLAN.md §13>

## AI usage
Planned with AI help and built with coding agents from PLAN.md / AGENTS.md (both in the repo). I reviewed
every phase and can explain every decision in docs/DESIGN_DECISIONS.md.
```
