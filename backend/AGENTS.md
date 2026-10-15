# AGENTS.md — how to work in this repo

Read this file, then `PLAN.md`, before writing code. `PLAN.md` is the spec; this file is the rulebook.

## 1. Workflow

1. Work on exactly one phase from `PLAN.md` §11 at a time. Re-read the sections it cites before starting.
2. Write the phase's tests first or alongside the code. A phase is done only when its gate passes.
3. Run `./mvnw -B verify` before every commit.
4. Commit in the steps the phase lists (Conventional Commits; several per phase).
5. Finish with a 5-line summary: files changed, tests added, deviations from `PLAN.md` and why, which `DESIGN_DECISIONS.md` sections the owner should read.
6. **Stop and ask** instead of guessing when: the plan is ambiguous; a provided file (migrations, `sql/checks`, scripts, infra) seems wrong; a test vector does not match; a library API differs from what the plan says; you would need a new dependency.

Prefer the plain, obvious way over the clever one: a reviewer should be able to follow any method in one read. Name things after what they do.

## 2. Commands

| Task | Command |
|---|---|
| Build + all tests (needs Docker) | `./mvnw -B verify` |
| One test class | `./mvnw -B test -Dtest=TransferApiIT` |
| Run locally with Postgres + demo data | `docker compose up --build` |
| Walk every requirement against it | `./scripts/demo.sh` (needs `jq`) |
| Tamper demo (local only) | `./scripts/tamper-demo.sh` |

## 3. Never do this

- Never use `double`, `float`, `Double` or `Float` for money. Amounts are `long` minor units. `BigDecimal` only inside FX rate maths.
- Never write `UPDATE`, `DELETE` or `TRUNCATE` against `journal_transactions`, `postings`, `fx_rates`, `currencies`, `reconciliation_*`, `account_period_balances`. The database rejects it; don't work around the triggers.
- Never edit `V1..V5` migrations or the `sql/checks` files. Schema changes go in a new `V6__…sql`, and only after asking.
- Never load an `Account` or `LedgerState` entity in a transaction before locking it (Hibernate will not refresh it when the lock query runs → lost updates). Pre-lock lookups use the `AccountCurrencyView` projection.
- Never lock accounts one by one or in request order — always one `lockAllByIdOrdered` call with every id the command touches, then `lockHead()` last. Nothing may lock `ledger_state` and then an account.
- Never run the funds check, the `externalRef` check or the "already reversed" check before the idempotency check (a retry of a successful request must replay, not be re-judged).
- Never add a retry loop around `LedgerCommandService`. A lock timeout becomes 503 `TRY_AGAIN`; the client retries with the same key.
- Never take timestamps that get stored or hashed from the JVM clock. Use `DbClock.now()` after `lockHead()`.
- Never return live account balances in a POST response — replays must be byte-identical.
- Never truncate or clean tables in tests (they are append-only). Each test creates its own accounts. Tests that write fake hashes or corrupt data (`SchemaGuardrailsIT`, `TamperDetectionIT`) get their own Spring context and container.
- Never log full request bodies.
- Never add Lombok, MapStruct, H2, spring-retry or Moneta.

## 4. Spring Boot 4 / Java 25 gotchas (your training data may predate these)

- Spring Boot **4.1**, Spring Framework **7**, Hibernate **7**, Jackson **3**. Generate the skeleton from start.spring.io; Boot 4 uses modular starters and module-specific test starters.
- Jackson 3 packages are `tools.jackson.*`, not `com.fasterxml.jackson.*` (annotations such as `@JsonProperty` may still come from `com.fasterxml.jackson.annotation` — follow what compiles with the Boot-managed version). If a `spring.jackson.*` property doesn't take effect, use a `JsonMapperBuilderCustomizer` in `JacksonConfig`. Behaviour proved by tests: unknown JSON fields → 400; `"amount": 10.5` → 400; `"amount": "100"` → 400 (scalar coercion off). Write `Instant` as ISO-8601 strings.
- Testcontainers **2**: artifact `org.testcontainers:testcontainers-postgresql`, class `org.testcontainers.postgresql.PostgreSQLContainer`, JUnit 4 support removed. Wire it with `@ServiceConnection` on a `@Bean` in a `@TestConfiguration`. The container's default user is a superuser (needed by `TamperDetectionIT`).
- In tests read the port with `@Value("${local.server.port}")` and call the API with `RestClient`.
- springdoc-openapi **3.1.x** for Boot 4.1 (3.0.x is for 4.0).
- Flyway: if migrations don't run against PostgreSQL, add `org.flywaydb:flyway-database-postgresql`.
- `ddl-auto=validate`: fix the JPA mapping (`@Column`, `@JdbcTypeCode`), never the migration. `smallint` ↔ `short`; `numeric(24,12)` ↔ `BigDecimal` with precision/scale; hash and currency columns are `varchar`.
- `@Lock(PESSIMISTIC_WRITE)` on PostgreSQL may render as `FOR UPDATE` or `FOR NO KEY UPDATE`; either serialises writers on the same row. Don't "fix" it.
- Hikari `connection-init-sql` sets `lock_timeout`, `statement_timeout` and `idle_in_transaction_session_timeout`. If the driver rejects the multi-statement string, move the settings into the JDBC URL `options` parameter — same values.
- Spring maps PostgreSQL 55P03 (lock timeout) and 40P01 (deadlock) to subclasses of `TransientDataAccessException`; a constraint trigger's `ERRCODE 23000` arrives as `DataIntegrityViolationException`, usually at flush or commit.

## 5. Code conventions

- Package by feature (`account`, `journal`, `fx`, `integrity`, `seed`) — see `PLAN.md` §4. Controllers call services, never repositories.
- Records for DTOs, commands and views. Entities are classes with no public setters.
- One `ErrorCode` enum; throw `LedgerException` with a code — never build error bodies by hand.
- Constructor injection only.
- Small, named methods over comments. Comment the *why* where a reviewer will ask: the numbered steps in `LedgerCommandService`, lock order, floor rounding, why the view never reads live balances.
- Tests are named by behaviour (`rejectsTransferWhenFundsAreInsufficient`). Assert outcomes and invariants, never timing. Concurrency tests use a `CountDownLatch` start gate and virtual threads.
- Keep `docs/DESIGN_DECISIONS.md` in sync: when a phase lands, fill the **Evidence** line of its ADRs with test class names.

## 6. Definition of done (every phase)

- [ ] Gate in `PLAN.md` §11 passes; `./mvnw -B verify` green; CI green.
- [ ] Names, paths, JSON fields and error codes match `PLAN.md` exactly.
- [ ] No new dependency, no migration edit, no `TODO` left behind.
- [ ] ADR Evidence lines updated.
- [ ] Commits made in the listed steps; summary posted.
