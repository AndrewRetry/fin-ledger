package com.andrewretry.ledger.integrity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Stores runs and their discrepancies. Plain SQL like the checks themselves; the tables are insert-only, so a run
 * is written once, at the end, together with everything it found.
 */
@Repository
public class ReconciliationRepository {

	private final JdbcTemplate jdbc;

	public ReconciliationRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void save(ReconciliationRun run, List<Discrepancy> discrepancies) {
		jdbc.update("""
				insert into reconciliation_runs (id, kind, period, provisional, status, started_at, finished_at,
				                                 checked_accounts, checked_transactions, discrepancy_count)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				run.id(), run.kind().name(), run.period(), run.provisional(), run.status(),
				Timestamp.from(run.startedAt()), Timestamp.from(run.finishedAt()), run.checkedAccounts(),
				run.checkedTransactions(), run.discrepancyCount());
		for (Discrepancy d : discrepancies) {
			jdbc.update("""
					insert into reconciliation_discrepancies
					  (id, run_id, check_code, account_id, transaction_id, currency, expected, actual, detail)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
					UUID.randomUUID(), run.id(), d.checkCode().name(), d.accountId(), d.transactionId(), d.currency(),
					d.expected(), d.actual(), d.detail());
		}
	}

	public Optional<ReconciliationRun> findRun(UUID id) {
		return jdbc.query("select * from reconciliation_runs where id = ?", (rs, row) -> new ReconciliationRun(
				rs.getObject("id", UUID.class),
				ReconciliationRun.Kind.valueOf(rs.getString("kind")),
				rs.getObject("period", LocalDate.class),
				rs.getBoolean("provisional"),
				instant(rs, "started_at"),
				instant(rs, "finished_at"),
				rs.getInt("checked_accounts"),
				rs.getInt("checked_transactions"),
				rs.getInt("discrepancy_count")), id).stream().findFirst();
	}

	public List<Discrepancy> findDiscrepancies(UUID runId) {
		return jdbc.query("""
				select check_code, account_id, transaction_id, currency, expected, actual, detail
				  from reconciliation_discrepancies where run_id = ?""", (rs, row) -> new Discrepancy(
				CheckCode.valueOf(rs.getString("check_code")),
				rs.getObject("account_id", UUID.class),
				rs.getObject("transaction_id", UUID.class),
				rs.getString("currency"),
				rs.getObject("expected", Long.class),
				rs.getObject("actual", Long.class),
				rs.getString("detail")), runId);
	}

	public boolean snapshotsExist(LocalDate period) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from account_period_balances where period = ?)", Boolean.class, period));
	}

	/** The database accepts these only from a clean, final month-end run of the same period. */
	public int saveSnapshots(UUID runId, List<AccountPeriodBalance> snapshots) {
		for (AccountPeriodBalance s : snapshots) {
			jdbc.update("""
					insert into account_period_balances (account_id, period, currency, opening_balance, period_debits,
					                                     period_credits, closing_balance, last_account_version, run_id)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
					s.accountId(), s.period(), s.currency(), s.opening(), s.debits(), s.credits(), s.closing(),
					s.lastAccountVersion(), runId);
		}
		return snapshots.size();
	}

	/** Month-end snapshots this run froze (0 for every integrity run). */
	public int countSnapshots(UUID runId) {
		return jdbc.queryForObject("select count(*) from account_period_balances where run_id = ?", Integer.class, runId);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, OffsetDateTime.class).toInstant();
	}

}
