package com.andrewretry.ledger.integrity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.andrewretry.ledger.common.Currencies;
import com.andrewretry.ledger.common.DbClock;
import com.andrewretry.ledger.common.Money;
import com.andrewretry.ledger.common.error.ErrorCode;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.config.LedgerProperties;
import com.andrewretry.ledger.integrity.dto.IntegrityReport;

/**
 * Month-end reconciliation (PLAN.md §7.9). For every account: last month's closing + this month's credits - debits
 * must land exactly on the balance the ledger recorded at month end. A clean close of a finished month freezes one
 * snapshot per account, so later edits to that month are caught when it is closed again.
 */
@Service
public class MonthEndService {

	private final NamedParameterJdbcTemplate jdbc;
	private final ReconciliationRepository runs;
	private final IntegrityHoldService holds;
	private final DbClock dbClock;
	private final Currencies currencies;
	private final Clock clock;
	private final ZoneId zone;
	private final TransactionTemplate oneSnapshot;
	private final TransactionTemplate newTransaction;
	private final String monthEndQuery = IntegrityService.sqlFile("month_end");

	public MonthEndService(NamedParameterJdbcTemplate jdbc, ReconciliationRepository runs, IntegrityHoldService holds,
			DbClock dbClock, Currencies currencies, Clock clock, LedgerProperties properties,
			PlatformTransactionManager tx) {
		this.jdbc = jdbc;
		this.runs = runs;
		this.holds = holds;
		this.dbClock = dbClock;
		this.currencies = currencies;
		this.clock = clock;
		this.zone = properties.close().zone();
		this.oneSnapshot = IntegrityService.readOnlySnapshot(tx);
		this.newTransaction = new TransactionTemplate(tx);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** One account's row of month_end.sql. The two snapshot columns are null when no snapshot exists. */
	private record Row(UUID accountId, String currency, long opening, long debits, long credits, long expectedClosing,
			long actualClosing, long lastAccountVersion, Long previousSnapshotClosing, Long existingSnapshotClosing) {
	}

	/** What one snapshot saw. */
	private record Findings(Instant startedAt, int transactions, List<Row> rows, List<Discrepancy> discrepancies) {
	}

	public IntegrityReport close(YearMonth period) {
		// Months follow ledger.close.zone (Singapore); timestamps are stored in UTC. Bounds are [start, end).
		Instant now = clock.instant();
		Instant periodStart = period.atDay(1).atStartOfDay(zone).toInstant();
		Instant periodEnd = period.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
		if (periodStart.isAfter(now)) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, "Period " + period + " has not started yet.");
		}
		boolean provisional = periodEnd.isAfter(now);   // the month is not over: check up to now, freeze nothing
		Instant checkedUntil = provisional ? now : periodEnd;
		LocalDate firstDay = period.atDay(1);

		// 1. Compare every account in one repeatable-read snapshot.
		Findings found = oneSnapshot.execute(status -> reconcile(periodStart, checkedUntil, firstDay));

		// 2. Store the run; if it is clean and final, and the month is not frozen yet, freeze it.
		ReconciliationRun run = new ReconciliationRun(UUID.randomUUID(), ReconciliationRun.Kind.MONTH_END, firstDay,
				provisional, found.startedAt(), dbClock.now(), found.rows().size(), found.transactions(),
				found.discrepancies().size());
		int snapshotsWritten = newTransaction.execute(status -> {
			runs.save(run, found.discrepancies());
			if (found.discrepancies().isEmpty() && !provisional && !runs.snapshotsExist(firstDay)) {
				return runs.saveSnapshots(run.id(), snapshotsOf(found.rows(), firstDay));
			}
			return 0;
		});

		// 3. Anything wrong stops the writes, as for the integrity checks.
		if (!found.discrepancies().isEmpty()) {
			holds.hold("month-end run " + run.id() + " for " + period + " found " + found.discrepancies().size()
					+ " discrepancies");
		}
		List<IntegrityReport.AccountLine> accounts = found.rows().stream().map(this::accountLine).toList();
		return IntegrityReport.of(run, null, null, accounts, snapshotsWritten, found.discrepancies(),
				holds.status().held());
	}

	private Findings reconcile(Instant periodStart, Instant periodEnd, LocalDate firstDay) {
		Instant startedAt = dbClock.now();
		Map<String, Object> params = Map.of(
				"periodStart", periodStart.atOffset(ZoneOffset.UTC),
				"periodEnd", periodEnd.atOffset(ZoneOffset.UTC),
				"period", firstDay,
				"previousPeriod", firstDay.minusMonths(1));
		List<Row> rows = jdbc.query(monthEndQuery, params, (rs, i) -> new Row(
				rs.getObject("account_id", UUID.class), rs.getString("currency"), rs.getLong("opening_balance"),
				rs.getLong("period_debits"), rs.getLong("period_credits"), rs.getLong("expected_closing"),
				rs.getLong("actual_closing"), rs.getLong("last_account_version"),
				rs.getObject("previous_snapshot_closing", Long.class), rs.getObject("existing_snapshot_closing", Long.class)));

		List<Discrepancy> discrepancies = new ArrayList<>();
		for (Row row : rows) {
			if (row.expectedClosing() != row.actualClosing()) {
				discrepancies.add(discrepancy(CheckCode.MONTH_END_CLOSING_MISMATCH, row, row.expectedClosing(),
						row.actualClosing(), "opening + credits - debits (expected) <> recorded closing (actual)"));
			}
			if (row.previousSnapshotClosing() != null && row.previousSnapshotClosing() != row.opening()) {
				discrepancies.add(discrepancy(CheckCode.MONTH_END_OPENING_MISMATCH, row, row.previousSnapshotClosing(),
						row.opening(), "last month's frozen closing (expected) <> this month's opening (actual)"));
			}
			if (row.existingSnapshotClosing() != null && row.existingSnapshotClosing() != row.actualClosing()) {
				discrepancies.add(discrepancy(CheckCode.MONTH_END_SNAPSHOT_MISMATCH, row, row.existingSnapshotClosing(),
						row.actualClosing(), "closing frozen at close (expected) <> closing recorded now (actual)"));
			}
		}
		int transactions = jdbc.queryForObject("""
				select count(*) from journal_transactions
				 where created_at >= :periodStart and created_at < :periodEnd""", params, Integer.class);
		return new Findings(startedAt, transactions, rows, discrepancies);
	}

	private static Discrepancy discrepancy(CheckCode code, Row row, long expected, long actual, String detail) {
		return new Discrepancy(code, row.accountId(), null, row.currency(), expected, actual, detail);
	}

	private static List<AccountPeriodBalance> snapshotsOf(List<Row> rows, LocalDate period) {
		return rows.stream().map(row -> new AccountPeriodBalance(row.accountId(), period, row.currency(), row.opening(),
				row.debits(), row.credits(), row.actualClosing(), row.lastAccountVersion())).toList();
	}

	private IntegrityReport.AccountLine accountLine(Row row) {
		String ccy = row.currency();
		return new IntegrityReport.AccountLine(row.accountId(), ccy, money(row.opening(), ccy), money(row.debits(), ccy),
				money(row.credits(), ccy), money(row.expectedClosing(), ccy), money(row.actualClosing(), ccy),
				row.expectedClosing() == row.actualClosing());
	}

	private Money money(long amount, String currency) {
		return Money.of(amount, currency, currencies);
	}

}
