-- V5: one FUNDING and one FX_LIQUIDITY account per currency. Both start empty.
-- On startup the app funds each FX_LIQUIDITY wallet through the normal deposit path
-- (idempotency key "seed-fxliq-<CCY>"), so every cent is in the journal and the hash chain.

INSERT INTO accounts (id, name, kind, currency)
SELECT gen_random_uuid(), 'Funding - outside world (' || code || ')', 'FUNDING', code
  FROM currencies;

INSERT INTO accounts (id, name, kind, currency)
SELECT gen_random_uuid(), 'House FX liquidity (' || code || ')', 'FX_LIQUIDITY', code
  FROM currencies;
