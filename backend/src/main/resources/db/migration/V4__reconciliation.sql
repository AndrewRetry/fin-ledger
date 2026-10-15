-- V4: integrity / month-end runs, their discrepancies, and month-end snapshots. All insert-only.
-- A run row is written once, at the end, together with its discrepancies.

CREATE TABLE reconciliation_runs (
    id                   uuid        PRIMARY KEY,
    kind                 varchar(16) NOT NULL CHECK (kind IN ('INTEGRITY', 'MONTH_END')),
    period               date,                       -- first day of the month (MONTH_END only)
    provisional          boolean     NOT NULL DEFAULT false,  -- MONTH_END of a month that has not ended yet
    status               varchar(16) NOT NULL CHECK (status IN ('CLEAN', 'DISCREPANCIES')),
    started_at           timestamptz NOT NULL,
    finished_at          timestamptz NOT NULL,
    checked_accounts     integer     NOT NULL CHECK (checked_accounts >= 0),
    checked_transactions integer     NOT NULL CHECK (checked_transactions >= 0),
    discrepancy_count    integer     NOT NULL CHECK (discrepancy_count >= 0),
    CONSTRAINT ck_runs_period CHECK (
        (kind = 'MONTH_END' AND period IS NOT NULL AND extract(day FROM period) = 1)
        OR (kind = 'INTEGRITY' AND period IS NULL AND NOT provisional)),
    CONSTRAINT ck_runs_status CHECK ((status = 'CLEAN') = (discrepancy_count = 0)),
    CONSTRAINT ck_runs_time CHECK (finished_at >= started_at)
);

CREATE TABLE reconciliation_discrepancies (
    id             uuid         PRIMARY KEY,
    run_id         uuid         NOT NULL REFERENCES reconciliation_runs (id),
    check_code     varchar(40)  NOT NULL,
    account_id     uuid,
    transaction_id uuid,
    currency       varchar(3),
    expected       bigint,
    actual         bigint,
    detail         varchar(500) NOT NULL
);

CREATE INDEX ix_discrepancies_run ON reconciliation_discrepancies (run_id);

-- Month-end snapshot per account. Frozen: written once, by the first CLEAN, final month-end run.
CREATE TABLE account_period_balances (
    account_id           uuid       NOT NULL REFERENCES accounts (id),
    period               date       NOT NULL CHECK (extract(day FROM period) = 1),
    currency             varchar(3) NOT NULL,
    opening_balance      bigint     NOT NULL,
    period_debits        bigint     NOT NULL CHECK (period_debits >= 0),
    period_credits       bigint     NOT NULL CHECK (period_credits >= 0),
    closing_balance      bigint     NOT NULL,
    last_account_version bigint     NOT NULL CHECK (last_account_version >= 0),
    run_id               uuid       NOT NULL REFERENCES reconciliation_runs (id),
    PRIMARY KEY (account_id, period),
    CONSTRAINT ck_apb_arithmetic CHECK (closing_balance = opening_balance + period_credits - period_debits)
);

-- Only a CLEAN, final MONTH_END run for the same period may write snapshots.
CREATE FUNCTION ledger_snapshot_from_clean_run() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM reconciliation_runs r
                    WHERE r.id = NEW.run_id AND r.kind = 'MONTH_END' AND r.status = 'CLEAN'
                      AND NOT r.provisional AND r.period = NEW.period) THEN
        RAISE EXCEPTION 'ledger: snapshot for % must come from a clean, final month-end run of that period',
            NEW.period USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_period_balances_from_clean_run BEFORE INSERT ON account_period_balances
    FOR EACH ROW EXECUTE FUNCTION ledger_snapshot_from_clean_run();

CREATE TRIGGER reconciliation_runs_append_only BEFORE UPDATE OR DELETE ON reconciliation_runs
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER reconciliation_runs_no_truncate BEFORE TRUNCATE ON reconciliation_runs
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER reconciliation_discrepancies_append_only BEFORE UPDATE OR DELETE ON reconciliation_discrepancies
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER reconciliation_discrepancies_no_truncate BEFORE TRUNCATE ON reconciliation_discrepancies
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER account_period_balances_append_only BEFORE UPDATE OR DELETE ON account_period_balances
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_change();
CREATE TRIGGER account_period_balances_no_truncate BEFORE TRUNCATE ON account_period_balances
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_forbid_change();
