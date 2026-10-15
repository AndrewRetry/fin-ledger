-- ACCOUNT_BALANCE: every account's cached balance and version agree with its postings.
--   balance        = sum(credits) - sum(debits) of its postings
--   version        = number of its postings, numbered 1..n with no gaps
--   last posting's balance_after = balance
-- One row per discrepancy: account_id, transaction_id, currency, expected, actual, detail.
WITH computed AS (
    SELECT a.id,
           a.currency,
           a.balance,
           a.version,
           coalesce(sum(CASE p.direction WHEN 'CREDIT' THEN p.amount ELSE -p.amount END), 0)::bigint
                                                    AS computed_balance,
           count(p.id)::bigint                      AS posting_count,
           coalesce(max(p.account_version), 0)      AS max_version
      FROM accounts a
      LEFT JOIN postings p ON p.account_id = a.id
     GROUP BY a.id
), last_posting AS (
    SELECT DISTINCT ON (p.account_id) p.account_id, p.balance_after
      FROM postings p
     ORDER BY p.account_id, p.account_version DESC
)
SELECT c.id AS account_id, NULL::uuid AS transaction_id, c.currency,
       c.computed_balance AS expected, c.balance AS actual,
       'cached balance (actual) <> credits - debits of its postings (expected)' AS detail
  FROM computed c
 WHERE c.balance <> c.computed_balance
UNION ALL
SELECT c.id, NULL::uuid, c.currency, c.posting_count, c.version,
       'version (actual) <> number of postings (expected), or versions have gaps'
  FROM computed c
 WHERE c.version <> c.posting_count OR c.max_version <> c.posting_count
UNION ALL
SELECT c.id, NULL::uuid, c.currency, lp.balance_after, c.balance,
       'cached balance (actual) <> balance_after of the last posting (expected)'
  FROM computed c
  JOIN last_posting lp ON lp.account_id = c.id
 WHERE lp.balance_after <> c.balance
