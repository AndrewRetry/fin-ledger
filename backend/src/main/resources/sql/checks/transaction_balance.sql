-- TRANSACTION_BALANCE: every transaction has at least 2 postings and balances per currency.
-- One row per discrepancy: account_id, transaction_id, currency, expected, actual, detail.
SELECT NULL::uuid                                                                  AS account_id,
       p.transaction_id                                                            AS transaction_id,
       p.currency                                                                  AS currency,
       coalesce(sum(p.amount) FILTER (WHERE p.direction = 'DEBIT'), 0)::bigint     AS expected,
       coalesce(sum(p.amount) FILTER (WHERE p.direction = 'CREDIT'), 0)::bigint    AS actual,
       'transaction debits (expected) <> credits (actual) in ' || p.currency       AS detail
  FROM postings p
 GROUP BY p.transaction_id, p.currency
HAVING coalesce(sum(p.amount) FILTER (WHERE p.direction = 'DEBIT'), 0)
    <> coalesce(sum(p.amount) FILTER (WHERE p.direction = 'CREDIT'), 0)
UNION ALL
SELECT NULL::uuid, t.id, NULL::varchar, 2::bigint, count(p.id)::bigint,
       'transaction has ' || count(p.id) || ' posting(s); at least 2 required'
  FROM journal_transactions t
  LEFT JOIN postings p ON p.transaction_id = t.id
 GROUP BY t.id
HAVING count(p.id) < 2
