-- V2: FX rates. Insert-only: a new rate is a new row, and the newest row for a pair is used.
-- Each FX transaction stores the id of the exact rate row it used.
--   rate = how many units of `quote` one unit of `base` buys (SGD -> USD 0.775: SGD 1.00 = USD 0.775)

CREATE TABLE fx_rates (
    id         uuid            PRIMARY KEY,
    base       varchar(3)      NOT NULL REFERENCES currencies (code),
    quote      varchar(3)      NOT NULL REFERENCES currencies (code),
    rate       numeric(24, 12) NOT NULL CHECK (rate > 0),
    source     varchar(16)     NOT NULL CHECK (source IN ('SEED', 'MANUAL', 'ECB')),
    created_at timestamptz     NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_fx_rates_pair CHECK (base <> quote)
);

CREATE INDEX ix_fx_rates_pair_latest ON fx_rates (base, quote, created_at DESC);

CREATE TRIGGER fx_rates_append_only BEFORE UPDATE OR DELETE ON fx_rates
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER fx_rates_no_truncate BEFORE TRUNCATE ON fx_rates
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_forbid_change();

-- Demo reference rates (NOT market data), derived from one table of USD values
-- (SGD 0.775, EUR 1.16, JPY 0.0066) so the 12 pairs agree with each other.
-- Replace them with POST /admin/fx-rates (source MANUAL).
INSERT INTO fx_rates (id, base, quote, rate, source)
SELECT gen_random_uuid(), r.base, r.quote, r.rate, 'SEED'
  FROM (VALUES
    ('SGD', 'USD', 0.775),
    ('SGD', 'EUR', 0.66810345),
    ('SGD', 'JPY', 117.42424),
    ('USD', 'SGD', 1.2903226),
    ('USD', 'EUR', 0.86206897),
    ('USD', 'JPY', 151.51515),
    ('EUR', 'SGD', 1.4967742),
    ('EUR', 'USD', 1.16),
    ('EUR', 'JPY', 175.75758),
    ('JPY', 'SGD', 0.008516129),
    ('JPY', 'USD', 0.0066),
    ('JPY', 'EUR', 0.0056896552)
  ) AS r (base, quote, rate);
