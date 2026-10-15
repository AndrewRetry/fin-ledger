-- MONTH_END: one row per account created before :periodEnd.
-- A posting's time is its transaction's created_at. Bounds are half-open [periodStart, periodEnd),
-- computed in Java from ledger.close.zone (Asia/Singapore) and passed as timestamptz.
-- For a month that has not ended yet, Java passes periodEnd = now (provisional run).
--   opening_balance  = balance the ledger recorded before the month (last posting's balance_after)
--   expected_closing = opening + the month's credits - the month's debits
--   actual_closing   = balance the ledger recorded at month end (last posting's balance_after)
-- Java then compares:
--   expected_closing  vs actual_closing   -> MONTH_END_CLOSING_MISMATCH
--   previous_snapshot_closing vs opening  -> MONTH_END_OPENING_MISMATCH   (last month's frozen closing)
--   existing_snapshot_closing vs actual   -> MONTH_END_SNAPSHOT_MISMATCH  (this month already frozen)
WITH timed AS (
    SELECT p.account_id, p.direction, p.amount, p.account_version, p.balance_after, t.created_at
      FROM postings p
      JOIN journal_transactions t ON t.id = p.transaction_id
     WHERE t.created_at < CAST(:periodEnd AS timestamptz)
), opening AS (
    SELECT DISTINCT ON (account_id) account_id, balance_after
      FROM timed
     WHERE created_at < CAST(:periodStart AS timestamptz)
     ORDER BY account_id, account_version DESC
), activity AS (
    SELECT account_id,
           sum(amount) FILTER (WHERE direction = 'DEBIT')  AS debits,
           sum(amount) FILTER (WHERE direction = 'CREDIT') AS credits
      FROM timed
     WHERE created_at >= CAST(:periodStart AS timestamptz)
     GROUP BY account_id
), closing AS (
    SELECT DISTINCT ON (account_id) account_id, balance_after, account_version
      FROM timed
     ORDER BY account_id, account_version DESC
)
SELECT a.id                                         AS account_id,
       a.currency                                   AS currency,
       coalesce(o.balance_after, 0)::bigint         AS opening_balance,
       coalesce(act.debits, 0)::bigint              AS period_debits,
       coalesce(act.credits, 0)::bigint             AS period_credits,
       (coalesce(o.balance_after, 0) + coalesce(act.credits, 0) - coalesce(act.debits, 0))::bigint
                                                    AS expected_closing,
       coalesce(c.balance_after, 0)::bigint         AS actual_closing,
       coalesce(c.account_version, 0)::bigint       AS last_account_version,
       prev.closing_balance                         AS previous_snapshot_closing,
       cur.closing_balance                          AS existing_snapshot_closing
  FROM accounts a
  LEFT JOIN opening  o   ON o.account_id   = a.id
  LEFT JOIN activity act ON act.account_id = a.id
  LEFT JOIN closing  c   ON c.account_id   = a.id
  LEFT JOIN account_period_balances prev
         ON prev.account_id = a.id AND prev.period = CAST(:previousPeriod AS date)
  LEFT JOIN account_period_balances cur
         ON cur.account_id  = a.id AND cur.period  = CAST(:period AS date)
 WHERE a.created_at < CAST(:periodEnd AS timestamptz)
 ORDER BY a.id
