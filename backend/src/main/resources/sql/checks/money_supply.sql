-- MONEY_SUPPLY: the assignment's "total money in the ledger matches the expected sum".
-- ONE ROW PER CURRENCY (not only discrepancies) - Java reports every row as a total and
-- turns rows where expected <> actual into discrepancies.
--   expected = net money that entered from outside = minus the FUNDING account's balance,
--              recomputed from its postings (deposits in, withdrawals out)
--   actual   = money held in wallets = sum of cached balances of CUSTOMER + FX_LIQUIDITY accounts
WITH inflow AS (
    SELECT a.currency,
           -coalesce(sum(CASE p.direction WHEN 'CREDIT' THEN p.amount ELSE -p.amount END), 0) AS expected
      FROM accounts a
      LEFT JOIN postings p ON p.account_id = a.id
     WHERE a.kind = 'FUNDING'
     GROUP BY a.currency
), held AS (
    SELECT a.currency, sum(a.balance) AS actual
      FROM accounts a
     WHERE a.kind <> 'FUNDING'
     GROUP BY a.currency
)
SELECT NULL::uuid                       AS account_id,
       NULL::uuid                       AS transaction_id,
       c.code                           AS currency,
       coalesce(i.expected, 0)::bigint  AS expected,
       coalesce(h.actual, 0)::bigint    AS actual,
       'money held in wallets (actual) vs net money deposited (expected) in ' || c.code AS detail
  FROM currencies c
  LEFT JOIN inflow i ON i.currency = c.code
  LEFT JOIN held   h ON h.currency = c.code
 ORDER BY c.code
