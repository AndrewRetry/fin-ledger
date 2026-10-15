# PLAN.md — Ledger Console (frontend build plan)

Version 1.0 · 2026-10-15

A browser console for the ledger in `backend/`. Its job is to let a reviewer **try every feature of the backend by
clicking**, see exactly what the backend answered, and run the assignment's scenarios (retries, concurrency,
reversals, FX, tampering detection) without curl. It is a tool, not a product: plain, dense, correct, and never
broken-looking.

The backend is the single source of truth. The console holds no business rules of its own: it sends requests,
shows responses, and explains them. Where it computes anything (an FX preview), it says "estimate" and the
server's answer wins.

---

## 0. How to use this plan

1. Work one phase at a time (§9). Each phase lists scope, commits and a "done when" gate.
2. Gate for every phase: `npm run build` (type-checks) and `npm test` green; the phase's flows work against
   `docker compose up --build`; no console errors in the browser; no `TODO` left.
3. Stop before each commit and let the owner commit (same rule as the backend).
4. Stop and ask when the backend's behaviour looks different from `backend/PLAN.md` §8 — never "fix" it in the UI.
5. No backend changes are needed for this plan. If one seems needed, stop and ask.

---

## 1. Locked decisions

| # | Topic | Decision | Why |
|---|---|---|---|
| F1 | Framework | **React 19 + TypeScript + Vite** | Fast to build, everyone can read it |
| F2 | UI kit | **Mantine 9** (`@mantine/core`, `hooks`, `form`, `notifications`, `modals`, `dates`) | Complete components out of the box: AppShell, tables, forms, modals, notifications, `ScrollArea` with designed scrollbars, `CopyButton`, dark mode. Almost no custom CSS |
| F3 | Icons | **Tabler icons** (`@tabler/icons-react`, Mantine's own set) | Consistent line icons. **No emojis anywhere** |
| F4 | Server state | **TanStack Query 5** | Caching, refetch after a write, loading/error states for free |
| F5 | API client | **openapi-typescript + openapi-fetch**, types generated from the backend's `/v3/api-docs` | Request and response types come from the real API, not hand-copied; one place to add headers |
| F6 | Routing | **React Router** (library mode) | Shareable URLs: `/accounts/{id}`, `/transactions/{id}` |
| F7 | Serving | Same origin through a proxy: browser calls `/api/...`; Vite dev server (dev) and nginx (Docker) strip `/api` and forward to the backend | The backend has no CORS configuration, and doesn't need one |
| F8 | Docker | New `web` service in `docker-compose.yml`: Node build stage → `nginx:alpine`, on **http://localhost:3000** | `docker compose up --build` starts database, backend and console together |
| F9 | Tests | **Vitest** for pure logic (money, problems, FX preview); **Playwright** smoke tests of the main flows against the Docker stack | Logic is proved cheaply; the e2e suite proves the screens actually work |
| F10 | Versions | Latest stable at build time, **exact versions** in `package.json`, lockfile committed. Scaffold with `npm create vite@latest` (template `react-ts`) and follow Mantine's Vite guide — don't hand-write configs from memory | Same rule as the backend |

Not used on purpose: Redux/Zustand (TanStack Query + a small React context is enough), Tailwind and
shadcn (more code to own than Mantine), chart libraries (nothing in the ledger needs a chart), any emoji.

---

## 2. What the backend gives us (from `backend/PLAN.md` §8)

All paths have no prefix on the backend; the console calls them under `/api`.

| Area | Endpoints | Console screen |
|---|---|---|
| Accounts | `POST /accounts`, `GET /accounts` (paged, ≤200), `GET /accounts/{id}`, `GET /accounts/{id}/transactions` (paged, oldest first) | Accounts, Account detail |
| Money movement | `POST /deposits`, `POST /withdrawals`, `POST /transactions` (FX when currencies differ), `POST /transactions/{id}/reversals` — all need `Idempotency-Key` | Move money, Transaction |
| Reads | `GET /transactions/{id}` (adds `reversedById`), `GET /ledger/head`, `GET /fx/rates` | Transaction, Overview, FX |
| Integrity | `POST /integrity/checks`, `GET /integrity/runs/{id}`, `POST /reconciliations/month-end?period=YYYY-MM` | Integrity |
| Admin (`X-Admin-Key`) | `GET /admin/integrity-hold`, `POST /admin/integrity-hold/release`, `POST /admin/fx-rates` | Admin, FX |

Behaviour the console must surface, not hide:

- Every response carries `X-Request-Id`; while a hold is on, every response carries `X-Ledger-Integrity: HOLD`.
- A replayed POST returns 201 with `Idempotent-Replayed: true` and a byte-identical body.
- Errors are RFC 9457 problems: `code`, `title`, `detail`, `requestId`, and `errors[{field, message}]` for validation.
- Money in responses is `{ amount, currency, display }`; requests take integer minor units.
- Demo accounts have fixed ids (Alice, Bob, Carol, Kenji — `backend/PLAN.md` §7.10); dev admin key is `dev-admin`.

---

## 3. Layout and look

- **Mantine `AppShell`**: fixed header (app name, ledger head, hold indicator, admin-key status, light/dark
  toggle) and a left navbar. Main content scrolls inside the shell; header and navbar never overlap content.
- **Navbar** (Tabler icons): Overview · Accounts · Move money · Transactions · FX rates · Integrity · Scenarios ·
  Admin.
- **Scrollbars**: wide tables live in Mantine `ScrollArea` (thin, themed, auto-hiding). The page scrollbar gets
  the same thin style through one small global CSS rule. No double scrollbars: the shell scrolls, cards don't.
- **Tables**: `Table` with sticky header, `tabular-nums`, amounts right-aligned, ids and hashes in a monospace
  `IdCell` (shortened `8398f93a…d824`, full value in a tooltip, `CopyButton` next to it).
- **Feedback**: Mantine notifications (top-right) for success and errors; `Skeleton` while loading; a clear empty
  state for every list ("No transactions yet"); buttons show a loading state and are disabled while a request runs,
  so double-clicks can't double-send.
- **Responsive**: works from 1024 px wide up; the navbar collapses to a burger below 768 px. Nothing overlaps at
  any width (checked in F6).
- **Colours mean something, consistently**: green = success/CLEAN/credit, red = rejected/DISCREPANCIES/debit,
  orange = hold/provisional, grey = replayed/neutral. Status words are always shown as text too, never colour only.

---

## 4. Cross-cutting pieces (build once in F0, use everywhere)

| Piece | Behaviour |
|---|---|
| `api/client.ts` | openapi-fetch client on `/api`. Middleware: adds `X-Admin-Key` to `/admin/*` when one is set; reads `X-Ledger-Integrity` and `X-Request-Id` from **every** response into the ledger-status context; turns a problem+json body into a typed `ApiProblem` error |
| `api/schema.ts` | Generated by `npm run gen:api` (openapi-typescript against `http://localhost:8080/v3/api-docs`). Committed; regenerated when the backend changes |
| `LedgerStatusContext` | `held` (from the header), last request id, admin key (kept in `sessionStorage`, never logged). Drives the header indicator and the hold banner |
| `HoldBanner` | Orange banner across the top while held: "Writes are stopped by an integrity hold. Reads still work." + reason (if the admin key is set) + link to Admin |
| `ProblemAlert` | Renders any `ApiProblem`: title, detail, `code` badge, copyable request id. Validation `errors` are also mapped onto the matching form fields |
| `money.ts` | `decimalsOf(money)` from the backend's own `display` string ("975.00" → 2, "50000" → 0) — no hard-coded currency table; `toMinor("25.5", 2) = 2550` and `fromMinor` with **string/BigInt maths only, never floats**; rejects more decimals than the currency has |
| `MoneyInput` | Mantine `NumberInput` in string mode, decimal scale = the account's decimals, currency shown as a suffix; shows the minor-unit value underneath ("= 2550 minor units") so the API contract stays visible |
| `IdempotencyField` | Every money form has a collapsed "Request details" section: the `Idempotency-Key` (a fresh UUID per new request, editable, "New key" button). The key is kept after a success so **"Send again with the same key"** can demonstrate a replay |
| `TransactionCard` | One component for every transaction everywhere: type badge, status, created at, description, external ref, FX box (rate, base→quote, source), postings table (account link, direction, amount, balance after, version), chain index, `prevHash`/`txHash`, links to `reversesId` / `reversedById`. Shows an "Idempotent replay" badge when the response said so |
| `useAllAccounts()` | Pages through `GET /accounts?size=200` for selects (the demo has few accounts); search by name or id |

---

## 5. Screens

### 5.1 Overview (`/`)
- Ledger head card: chain length, head hash (copy), "Record this hash outside the system" hint.
- Integrity card: hold state; "Run integrity checks" button → status, money-supply-per-currency mini table.
- Demo accounts table: the four fixed ids with live balances, each linking to its detail page.
- "Start here" list linking to Scenarios.

### 5.2 Accounts (`/accounts`)
- Paged table (page size selector 20/50/100): name, kind badge (CUSTOMER / FX_LIQUIDITY / FUNDING), currency,
  balance (display), version, created at, id. Search box filters the current page.
- "New account" modal: name, currency select (SGD, USD, EUR, JPY + free text so `CURRENCY_NOT_SUPPORTED` can be
  shown). Field errors come from the API's `errors`.

### 5.3 Account detail (`/accounts/:id`)
- Header: name, kind, currency, large balance, version; actions **Deposit**, **Withdraw**, **Transfer from here**
  (open the Move-money forms pre-filled).
- History table (API order: oldest first, by account version): when, type, direction, amount, balance after,
  version, description, transaction link. Pagination with a "Latest" button that jumps to the last page.
- System accounts (FUNDING, FX_LIQUIDITY) show a short note on what they mean (FUNDING is ≤ 0 by design).

### 5.4 Move money (`/move`)
Three tabs, one form each, all built from `MoneyInput` + `IdempotencyField`:
- **Transfer**: source and destination (searchable selects showing currency and balance), amount in the source
  currency, optional description, optional "send currency" field (to demonstrate `CURRENCY_MISMATCH`).
  When currencies differ: an **FX preview** box — rate in use (from `/fx/rates`), estimated amount received
  (same floor rule as `FxConversion`, BigInt maths), "estimate, the server decides".
- **Deposit / Withdraw**: account, amount, description, optional `externalRef` (with an "Example Fireblocks id"
  generator button).
- After submit: `TransactionCard` of the result, plus **Send again (same key)**, **New request**, **Reverse**,
  **Open account**. Rejections show `ProblemAlert` and leave the form filled, so the user can fix and resend.

### 5.5 Transactions (`/transactions`, `/transactions/:id`)
- Lookup by id (paste box; history rows and results link here).
- Detail: `TransactionCard` with `reversedById`; **Reverse** button → modal with required reason (1–280),
  its own idempotency key, result shown in place. Reversals of reversals and second reversals show the API's
  409/422 problem.
- "Verify hash" panel: recomputes `txHash` in the browser from the card's own fields (§7.6 canonical encoding,
  Web Crypto SHA-256) and shows match / mismatch — the "auditor can check from the API alone" claim, made visible.

### 5.6 FX rates (`/fx`)
- Table of the rate in use per pair (base, quote, rate, source badge SEED/MANUAL, created at).
- "Add rate" form (admin key required; disabled with an explanation when no key is set): base, quote, rate as a
  decimal string. Success refreshes the table; the new row is highlighted.

### 5.7 Integrity (`/integrity`)
- **Integrity checks**: run button → report: status badge, started/finished, checked accounts/transactions,
  checks table (code, discrepancy count), money supply table (currency, expected, actual, matches), chain card
  (length, head hash, valid), discrepancies table (check code, account/transaction links, currency, expected,
  actual, detail). Run id is copyable and opens the stored-run view.
- **Stored run** lookup by id (`GET /integrity/runs/{id}`) — explains why money supply/chain/accounts are empty.
- **Month-end**: month picker (`YYYY-MM`) → report with provisional badge, snapshots written, accounts table
  (opening, debits, credits, expected closing, actual closing, matches), the three MONTH_END checks.

### 5.8 Scenarios (`/scenarios`) — the showcase
A guided page that walks every requirement of the assignment, like `backend/scripts/demo.sh` but in the browser.
Each scenario is a card: what it proves, a **Run** button, then a step list with PASS/FAIL per step and the
requests/responses it made (expandable). Every scenario creates its own accounts, so they can be run in any order
and repeatedly.

| Scenario | Steps (each asserted on the real responses) |
|---|---|
| Atomic transfer | create A and B, deposit into A, transfer, both balances moved by the same amount, 2 postings |
| Insufficient funds | transfer more than the balance → 422 `INSUFFICIENT_FUNDS`, balances unchanged |
| Retry safety | same key + same body → 201 replay, same id, `Idempotent-Replayed`; same key + other body → 422; money moved once |
| Idempotency race | 20 identical requests fired at once with one key → 1 transaction id, 19 replays, money moved once |
| Concurrency | 20 different transfers into one account at once → all 201, receiver up by exactly 20×, nothing lost |
| History | deposit + transfers → history oldest first with correct running balances |
| Reversal | reverse a transfer → balances restored; reverse again → 409; reverse the reversal → 422 |
| Cross-currency | SGD → USD at the seeded rate, 4 postings, converted amount rounded down; reverse it exactly |
| External reference | deposit with `externalRef`, same ref under a new key → 409, credited once |
| Integrity | run checks → CLEAN, money supply matches, chain valid, head = last transaction |
| Month-end | close this month → provisional, CLEAN |
| Run all | runs every scenario in sequence and shows a summary (n passed / n failed) |

(Browsers send about six requests at a time to one host, so "at once" is a burst, not true parallelism — still
enough to exercise the row locks and the replay path; the backend's `ConcurrencyIT` covers the heavy case.)

### 5.9 Admin (`/admin`)
- Admin key field (stored for this browser session only; "Test key" button calls `GET /admin/integrity-hold`).
- Hold status card (held, reason, since) and **Release** with a required reason.
- Short explainer of how to trigger a real hold locally (`backend/scripts/tamper-demo.sh`) and that the next
  integrity run re-applies it if data is still broken.

---

## 6. Repository layout

```
frontend/
├── PLAN.md                 this file
├── package.json  package-lock.json  vite.config.ts  tsconfig*.json  index.html
├── Dockerfile  nginx.conf  .dockerignore
├── src/
│   ├── main.tsx  App.tsx  theme.ts  global.css        (MantineProvider, router, QueryClient)
│   ├── api/                client.ts  schema.ts (generated)  problem.ts  queries.ts
│   ├── lib/                money.ts  fxPreview.ts  txHash.ts  ids.ts
│   ├── components/         AppLayout  HoldBanner  ProblemAlert  IdCell  MoneyInput  IdempotencyField
│   │                       TransactionCard  PostingsTable  StatusBadge  EmptyState
│   ├── pages/              Overview  Accounts  AccountDetail  MoveMoney  Transactions  TransactionDetail
│   │                       FxRates  Integrity  Scenarios  Admin  NotFound
│   └── scenarios/          one file per scenario + runner.ts
├── tests/unit/             money, problem, fxPreview, txHash (Appendix A vectors from backend/PLAN.md)
└── tests/e2e/              Playwright smoke specs
```

`nginx.conf`: serve the built files, SPA fallback to `index.html`, `location /api/ { proxy_pass http://app:8080/; }`
(the trailing slash strips `/api`), pass `X-Request-Id` through. `vite.config.ts`: the same `/api` proxy to
`http://localhost:8080` for `npm run dev`.

---

## 7. Rules (the frontend's "never do this")

- Never compute money with JS floats. Minor units are integers; conversions use strings/BigInt.
- Never decide business outcomes in the UI (funds checks, FX amounts). Show the server's answer.
- Never send a money-moving request without an `Idempotency-Key`, and never silently change the key on a retry.
- Never hide a backend error: show `code`, `detail` and the request id.
- Never use emojis; use Tabler icons with a text label or tooltip.
- Never store the admin key beyond `sessionStorage`, never log it, never put it in a URL.
- Never hard-code demo data that the backend can tell us (balances, rates, decimals).
- Prefer Mantine components over custom CSS; global CSS is limited to the scrollbar and font smoothing.

---

## 8. Testing

- **Unit (Vitest)**: `toMinor`/`fromMinor`/`decimalsOf` (2 and 0 decimals, negatives, too many decimals, huge
  values), `ApiProblem` parsing, FX preview against the backend's Appendix A FX vectors, `txHash` against the
  Appendix A hash vectors.
- **E2E (Playwright, Chromium)**, against `docker compose up --build`: open Overview; create an account; deposit;
  transfer and see the result card; replay with the same key and see the replay badge; reverse; FX transfer shows
  4 postings; run integrity checks → CLEAN; run all Scenarios → all PASS. Plus one layout check at 1024 px and
  768 px that the navbar and content don't overlap.

---

## 9. Phases

| Phase | Time | Scope | Commits | Done when |
|---|---|---|---|---|
| **F0 Skeleton** | 1 h | Vite react-ts scaffold, Mantine + Tabler + TanStack Query + Router, theme, `AppLayout` (header + navbar + routes with placeholder pages), `api/client.ts` + generated `schema.ts`, `LedgerStatusContext`, `ProblemAlert`, Vite proxy, `Dockerfile` + `nginx.conf`, `web` service in `docker-compose.yml` | `chore(web): scaffold console` · `feat(web): api client, layout, status` | `docker compose up --build` → http://localhost:3000 shows the shell with the live ledger head |
| **F1 Accounts** | 1 h | `money.ts` + tests, `IdCell`, `MoneyInput`, Accounts list, create modal, Account detail + history | `feat(web): money helpers` · `feat(web): accounts` | create an account, see it listed, open it; validation errors land on fields |
| **F2 Moving money** | 1.5 h | `IdempotencyField`, Transfer / Deposit / Withdraw forms, FX preview, `TransactionCard`, Transaction detail, reversals, replay button, hash verify | `feat(web): transfers, deposits, withdrawals` · `feat(web): transaction detail and reversals` | every money flow works from the UI, including replay and FX |
| **F3 FX and admin** | 0.5 h | FX rates page + add rate, Admin page (key, hold, release), `HoldBanner` | `feat(web): fx rates and admin` | add a rate and see it used; hold banner appears during `tamper-demo.sh` and clears after release |
| **F4 Integrity** | 1 h | Integrity checks report, stored run lookup, month-end | `feat(web): integrity and month-end` | reports render fully for CLEAN and for a tampered ledger |
| **F5 Scenarios** | 1.5 h | Scenario runner + every scenario in §5.8 + Run all | `feat(web): guided scenarios` | Run all → every step PASS on a fresh stack, twice in a row |
| **F6 Polish and e2e** | 1 h | Loading/empty/error states audit, responsive check, Playwright suite, root README "Console" section (how to open it, what each page shows, screenshots) | `test(web): e2e smoke` · `docs: console in README` | Playwright green; no layout overlap at 768/1024/1440 px; no console errors |

Total ≈ 7.5 h. If time runs short, cut F6 screenshots and the hash-verify panel first, never a scenario.

---

## 10. Open questions for the owner (answer before F0)

1. Mantine as the UI kit (recommended) — or another kit you prefer?
2. Console served by Docker on port 3000 next to the API on 8080 (recommended) — or only via `npm run dev`?
3. Playwright e2e in F6 (recommended) — or unit tests only, to save time?
4. Should CI (`.github/workflows/ci.yaml`) also build and unit-test the frontend? (Recommended: yes, a second job,
   no e2e in CI.)
