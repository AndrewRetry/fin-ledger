-- V3: the journal, and the guards that tie journal, accounts and ledger_state together.
-- A journal transaction is >= 2 postings that balance per currency (debits = credits).
-- Both tables are append-only; corrections are new REVERSAL transactions.
-- Every transaction is a link in a hash chain: tx_hash covers prev_hash, so editing any
-- transaction breaks every later link.

CREATE TABLE journal_transactions (
    id              uuid         PRIMARY KEY,
    chain_index     bigint       NOT NULL CHECK (chain_index >= 1),     -- 1, 2, 3 ... no gaps
    type            varchar(16)  NOT NULL
                    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'FX_TRANSFER', 'REVERSAL')),
    idempotency_key varchar(255) NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    request_hash    varchar(64)  NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- The outside world's id for this money movement (e.g. a Fireblocks transaction id or a bank
    -- reference). Optional; deposits and withdrawals only; never used twice.
    external_ref    varchar(128) CHECK (external_ref ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    reverses_id     uuid         REFERENCES journal_transactions (id),
    fx_rate_id      uuid         REFERENCES fx_rates (id),
    description     varchar(280),
    created_at      timestamptz  NOT NULL,            -- from the database clock, taken under the locks
    prev_hash       varchar(64)  NOT NULL CHECK (prev_hash ~ '^[0-9a-f]{64}$'),
    tx_hash         varchar(64)  NOT NULL CHECK (tx_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_jt_chain_index UNIQUE (chain_index),
    CONSTRAINT uq_jt_idempotency_key UNIQUE (idempotency_key),   -- a key can never move money twice
    CONSTRAINT uq_jt_reverses_id UNIQUE (reverses_id),           -- reversed at most once
    CONSTRAINT uq_jt_external_ref UNIQUE (external_ref),         -- one outside movement, credited once
    CONSTRAINT ck_jt_external_ref_kind CHECK (external_ref IS NULL OR type IN ('DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT uq_jt_tx_hash UNIQUE (tx_hash),
    CONSTRAINT ck_jt_reversal_link CHECK ((type = 'REVERSAL') = (reverses_id IS NOT NULL)),
    CONSTRAINT ck_jt_fx_link CHECK ((type = 'FX_TRANSFER') = (fx_rate_id IS NOT NULL)),
    CONSTRAINT ck_jt_not_self_reversal CHECK (reverses_id IS NULL OR reverses_id <> id)
);

CREATE INDEX ix_jt_created_at ON journal_transactions (created_at);

CREATE TABLE postings (
    id              uuid        PRIMARY KEY,
    transaction_id  uuid        NOT NULL REFERENCES journal_transactions (id),
    account_id      uuid        NOT NULL,
    currency        varchar(3)  NOT NULL,
    direction       varchar(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount          bigint      NOT NULL CHECK (amount > 0),
    account_version bigint      NOT NULL CHECK (account_version >= 1),  -- the account's version after this posting
    balance_after   bigint      NOT NULL,                               -- the account's balance after this posting
    -- The posting's currency must be its account's currency.
    CONSTRAINT fk_postings_account_currency FOREIGN KEY (account_id, currency)
        REFERENCES accounts (id, currency),
    -- Two writers can never claim the same version of an account (lost-update detector).
    CONSTRAINT uq_postings_account_version UNIQUE (account_id, account_version),
    -- An account appears at most once per transaction.
    CONSTRAINT uq_postings_tx_account UNIQUE (transaction_id, account_id)
);

CREATE INDEX ix_postings_transaction ON postings (transaction_id);

-- ---------------------------------------------------------------------------
-- Insert-time: a new transaction must be the next link of the chain.
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger_chain_link() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    last_index bigint;
    last_hash  varchar(64);
BEGIN
    SELECT chain_index, tx_hash INTO last_index, last_hash
      FROM journal_transactions ORDER BY chain_index DESC LIMIT 1;
    IF NOT FOUND THEN
        last_index := 0;
        last_hash  := repeat('0', 64);
    END IF;
    IF NEW.chain_index <> last_index + 1 THEN
        RAISE EXCEPTION 'ledger: chain index % is not the next index %', NEW.chain_index, last_index + 1
            USING ERRCODE = '23000';
    END IF;
    IF NEW.prev_hash <> last_hash THEN
        RAISE EXCEPTION 'ledger: prev_hash of transaction % does not link to chain index %', NEW.id, last_index
            USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_transactions_chain_link BEFORE INSERT ON journal_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_chain_link();

-- ---------------------------------------------------------------------------
-- Commit-time check 1 (per posting): its transaction has >= 2 postings and balances per
-- currency, and its account was moved forward to (at least) this posting's version.
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger_postings_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    n            integer;
    bad          record;
    acct_version bigint;
BEGIN
    SELECT count(*) INTO n FROM postings WHERE transaction_id = NEW.transaction_id;
    IF n < 2 THEN
        RAISE EXCEPTION 'ledger: transaction % has % posting(s); at least 2 required',
            NEW.transaction_id, n USING ERRCODE = '23000';
    END IF;

    SELECT currency,
           sum(CASE direction WHEN 'DEBIT'  THEN amount ELSE 0 END) AS debits,
           sum(CASE direction WHEN 'CREDIT' THEN amount ELSE 0 END) AS credits
      INTO bad
      FROM postings
     WHERE transaction_id = NEW.transaction_id
     GROUP BY currency
    HAVING sum(CASE direction WHEN 'DEBIT'  THEN amount ELSE 0 END)
        <> sum(CASE direction WHEN 'CREDIT' THEN amount ELSE 0 END)
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'ledger: transaction % is unbalanced in %: debits % <> credits %',
            NEW.transaction_id, bad.currency, bad.debits, bad.credits USING ERRCODE = '23000';
    END IF;

    SELECT version INTO acct_version FROM accounts WHERE id = NEW.account_id;
    IF acct_version < NEW.account_version THEN
        RAISE EXCEPTION 'ledger: posting % claims version % but account % is still at version %',
            NEW.id, NEW.account_version, NEW.account_id, acct_version USING ERRCODE = '23000';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_postings_balanced();

-- ---------------------------------------------------------------------------
-- Commit-time check 2 (per transaction): it has postings, it advanced the chain head,
-- and a reversal exactly mirrors its original (same accounts, currencies and amounts,
-- debit and credit swapped). A reversal can never itself be reversed.
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger_transaction_complete() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    n             integer;
    head_length   bigint;
    original_type varchar(16);
BEGIN
    SELECT count(*) INTO n FROM postings WHERE transaction_id = NEW.id;
    IF n < 2 THEN
        RAISE EXCEPTION 'ledger: transaction % committed with % posting(s)', NEW.id, n
            USING ERRCODE = '23000';
    END IF;

    SELECT chain_length INTO head_length FROM ledger_state WHERE id = 1;
    IF head_length < NEW.chain_index THEN
        RAISE EXCEPTION 'ledger: transaction % was added without advancing the chain head', NEW.id
            USING ERRCODE = '23000';
    END IF;

    IF NEW.reverses_id IS NOT NULL THEN
        SELECT type INTO original_type FROM journal_transactions WHERE id = NEW.reverses_id;
        IF original_type = 'REVERSAL' THEN
            RAISE EXCEPTION 'ledger: reversal % targets reversal %; a reversal cannot be reversed',
                NEW.id, NEW.reverses_id USING ERRCODE = '23000';
        END IF;

        IF EXISTS (
            (SELECT account_id, currency, amount,
                    CASE direction WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END
               FROM postings WHERE transaction_id = NEW.reverses_id
             EXCEPT
             SELECT account_id, currency, amount, direction
               FROM postings WHERE transaction_id = NEW.id)
            UNION ALL
            (SELECT account_id, currency, amount, direction
               FROM postings WHERE transaction_id = NEW.id
             EXCEPT
             SELECT account_id, currency, amount,
                    CASE direction WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END
               FROM postings WHERE transaction_id = NEW.reverses_id)
        ) THEN
            RAISE EXCEPTION 'ledger: reversal % does not mirror transaction %', NEW.id, NEW.reverses_id
                USING ERRCODE = '23000';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_transactions_complete
    AFTER INSERT ON journal_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_transaction_complete();

-- ---------------------------------------------------------------------------
-- Commit-time check 3 (per account update): a balance can only change together with a
-- posting that records exactly that version and balance. No posting, no balance change.
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger_account_backed_by_posting() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.version = OLD.version AND NEW.balance = OLD.balance THEN
        RETURN NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM postings
                    WHERE account_id = NEW.id
                      AND account_version = NEW.version
                      AND balance_after = NEW.balance) THEN
        RAISE EXCEPTION 'ledger: account % moved to balance % (version %) without a matching posting',
            NEW.id, NEW.balance, NEW.version USING ERRCODE = '23000';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER accounts_backed_by_postings
    AFTER UPDATE ON accounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_account_backed_by_posting();

-- ---------------------------------------------------------------------------
-- Commit-time check 4 (per head move): ledger_state must point at the newest transaction.
-- Only runs when the head moved, so setting or releasing a hold is never blocked.
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger_state_head_check() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    last_index bigint;
    last_hash  varchar(64);
BEGIN
    IF NEW.chain_length = OLD.chain_length AND NEW.head_hash = OLD.head_hash THEN
        RETURN NULL;
    END IF;
    SELECT chain_index, tx_hash INTO last_index, last_hash
      FROM journal_transactions ORDER BY chain_index DESC LIMIT 1;
    IF NOT FOUND THEN
        last_index := 0;
        last_hash  := repeat('0', 64);
    END IF;
    IF NEW.chain_length <> last_index OR NEW.head_hash <> last_hash THEN
        RAISE EXCEPTION 'ledger: chain head (%) does not match the newest transaction (%)',
            NEW.chain_length, last_index USING ERRCODE = '23000';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_state_head
    AFTER UPDATE ON ledger_state
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_state_head_check();

-- Append-only.
CREATE TRIGGER journal_transactions_append_only BEFORE UPDATE OR DELETE ON journal_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER journal_transactions_no_truncate BEFORE TRUNCATE ON journal_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER postings_append_only BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER postings_no_truncate BEFORE TRUNCATE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_forbid_change();
