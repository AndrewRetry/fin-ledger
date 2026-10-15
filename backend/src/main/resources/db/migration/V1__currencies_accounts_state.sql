-- V1: currencies, accounts, and the single ledger_state row (hash-chain head + integrity hold).
-- Works on PostgreSQL 16+. Every guard raises ERRCODE 23000 so Spring maps it to
-- DataIntegrityViolationException.

-- Shared guard: any UPDATE / DELETE / TRUNCATE on an append-only table fails.
CREATE FUNCTION ledger_forbid_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger: % on % is forbidden (append-only table)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = '23000';
END;
$$;

-- ---------------------------------------------------------------------------
-- Currencies: ISO 4217 code + number of decimals ("minor units"): SGD 2 = cents, JPY 0.
-- ---------------------------------------------------------------------------
CREATE TABLE currencies (
    code        varchar(3) PRIMARY KEY CHECK (code ~ '^[A-Z]{3}$'),
    minor_units smallint   NOT NULL CHECK (minor_units BETWEEN 0 AND 4)
);

INSERT INTO currencies (code, minor_units) VALUES
    ('SGD', 2),
    ('USD', 2),
    ('EUR', 2),
    ('JPY', 0);

CREATE TRIGGER currencies_append_only BEFORE UPDATE OR DELETE ON currencies
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER currencies_no_truncate BEFORE TRUNCATE ON currencies
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_forbid_change();

-- ---------------------------------------------------------------------------
-- Accounts. One currency each. Every account uses the same rule:
--   CREDIT = money in (balance goes up), DEBIT = money out (balance goes down).
--   CUSTOMER      a customer wallet                         balance >= 0
--   FX_LIQUIDITY  the house's wallet used for FX conversion  balance >= 0
--   FUNDING       the outside world (deposits come from it)  balance <= 0
--                 = minus the net money deposited, so each currency's balances sum to zero.
-- balance is a cache of the postings; only balance and version ever change.
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    id         uuid         PRIMARY KEY,
    name       varchar(100) NOT NULL CHECK (length(btrim(name)) > 0),
    kind       varchar(16)  NOT NULL CHECK (kind IN ('CUSTOMER', 'FX_LIQUIDITY', 'FUNDING')),
    currency   varchar(3)   NOT NULL REFERENCES currencies (code),
    balance    bigint       NOT NULL DEFAULT 0,
    version    bigint       NOT NULL DEFAULT 0 CHECK (version >= 0),   -- number of postings so far
    created_at timestamptz  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_accounts_id_currency UNIQUE (id, currency),          -- target of the postings FK
    CONSTRAINT ck_accounts_non_negative CHECK (kind = 'FUNDING' OR balance >= 0),
    CONSTRAINT ck_accounts_funding_non_positive CHECK (kind <> 'FUNDING' OR balance <= 0)
);

-- Exactly one FUNDING and one FX_LIQUIDITY account per currency.
CREATE UNIQUE INDEX uq_accounts_system_per_currency ON accounts (kind, currency) WHERE kind <> 'CUSTOMER';
CREATE INDEX ix_accounts_created_at ON accounts (created_at);

CREATE FUNCTION ledger_accounts_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.balance <> 0 OR NEW.version <> 0 THEN
            RAISE EXCEPTION 'ledger: account % must start empty (balance 0, version 0)', NEW.id
                USING ERRCODE = '23000';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.id <> OLD.id OR NEW.name <> OLD.name OR NEW.kind <> OLD.kind
       OR NEW.currency <> OLD.currency OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'ledger: identity columns of account % are immutable', OLD.id
            USING ERRCODE = '23000';
    END IF;
    IF NEW.version < OLD.version THEN
        RAISE EXCEPTION 'ledger: version of account % cannot go down', OLD.id
            USING ERRCODE = '23000';
    END IF;
    IF NEW.balance <> OLD.balance AND NEW.version = OLD.version THEN
        RAISE EXCEPTION 'ledger: balance of account % changed without a new version', OLD.id
            USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_guard BEFORE INSERT OR UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION ledger_accounts_guard();
CREATE TRIGGER accounts_no_delete BEFORE DELETE ON accounts
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER accounts_no_truncate BEFORE TRUNCATE ON accounts
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_forbid_change();

-- ---------------------------------------------------------------------------
-- ledger_state: exactly one row.
--   chain_length / head_hash  the newest transaction of the hash chain (0 / 64 zeros when empty)
--   integrity_hold            fail-closed switch: while true, no money moves
-- Every write locks this row (SELECT ... FOR UPDATE) as its LAST lock, so transactions
-- join the chain one at a time and a hold can never be skipped.
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_state (
    id             smallint     PRIMARY KEY CHECK (id = 1),
    chain_length   bigint       NOT NULL DEFAULT 0 CHECK (chain_length >= 0),
    head_hash      varchar(64)  NOT NULL DEFAULT repeat('0', 64) CHECK (head_hash ~ '^[0-9a-f]{64}$'),
    integrity_hold boolean      NOT NULL DEFAULT false,
    hold_reason    varchar(500),
    hold_since     timestamptz,
    CONSTRAINT ck_ledger_state_hold CHECK (
        (integrity_hold AND hold_reason IS NOT NULL AND hold_since IS NOT NULL)
        OR (NOT integrity_hold AND hold_reason IS NULL AND hold_since IS NULL))
);

INSERT INTO ledger_state (id) VALUES (1);

CREATE FUNCTION ledger_state_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.chain_length = OLD.chain_length THEN
        IF NEW.head_hash <> OLD.head_hash THEN
            RAISE EXCEPTION 'ledger: chain head changed without a new transaction'
                USING ERRCODE = '23000';
        END IF;
    ELSIF NEW.chain_length <> OLD.chain_length + 1 THEN
        RAISE EXCEPTION 'ledger: chain head can only advance one transaction at a time (% -> %)',
            OLD.chain_length, NEW.chain_length USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_state_guard BEFORE UPDATE ON ledger_state
    FOR EACH ROW EXECUTE FUNCTION ledger_state_guard();
CREATE TRIGGER ledger_state_no_delete BEFORE DELETE ON ledger_state
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER ledger_state_no_truncate BEFORE TRUNCATE ON ledger_state
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_forbid_change();
