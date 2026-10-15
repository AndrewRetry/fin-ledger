-- TRIAL_BALANCE: money is only moved, never created.
-- Per currency: total debits = total credits over all postings, and all cached account
-- balances sum to zero (the FUNDING account's negative balance offsets every wallet).
-- One row per discrepancy: account_id, transaction_id, currency, expected, actual, detail.
SELECT NULL::uuid                                                    AS account_id,
       NULL::uuid                                                    AS transaction_id,
       p.currency                                                    AS currency,
       coalesce(sum(p.amount) FILTER (WHERE p.direction = 'DEBIT'), 0)::bigint  AS expected,
       coalesce(sum(p.amount) FILTER (WHERE p.direction = 'CREDIT'), 0)::bigint AS actual,
       'total debits (expected) <> total credits (actual) for ' || p.currency   AS detail
  FROM postings p
 GROUP BY p.currency
HAVING coalesce(sum(p.amount) FILTER (WHERE p.direction = 'DEBIT'), 0)
    <> coalesce(sum(p.amount) FILTER (WHERE p.direction = 'CREDIT'), 0)
UNION ALL
SELECT NULL::uuid, NULL::uuid, a.currency, 0::bigint, sum(a.balance)::bigint,
       'account balances in ' || a.currency || ' sum to ' || sum(a.balance) || ' instead of 0'
  FROM accounts a
 GROUP BY a.currency
HAVING sum(a.balance) <> 0
