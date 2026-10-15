package com.andrewretry.ledger.integrity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.andrewretry.ledger.common.Currencies;
import com.andrewretry.ledger.common.DbClock;
import com.andrewretry.ledger.common.Money;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.integrity.dto.IntegrityReport;

/**
 * Re-adds everything from scratch and compares it with what was stored (PLAN.md §7.8). Any discrepancy turns on
 * the integrity hold: better to stop moving money than to move it on numbers proved wrong.
 */
@Service
public class IntegrityService {

	private final JdbcTemplate jdbc;
	private final ChainVerifier chainVerifier;
	private final ReconciliationRepository runs;
	private final IntegrityHoldService holds;
	private final DbClock dbClock;
	private final Currencies currencies;
	private final TransactionTemplate oneSnapshot;
	private final TransactionTemplate newTransaction;

	/** The provided SQL files, read once. Each returns one row per discrepancy (money supply: one per currency). */
	private final Map<CheckCode, String> discrepancyQueries = new LinkedHashMap<>();
	private final String moneySupplyQuery;

	public IntegrityService(JdbcTemplate jdbc, ChainVerifier chainVerifier, ReconciliationRepository runs,
			IntegrityHoldService holds, DbClock dbClock, Currencies currencies, PlatformTransactionManager tx) {
		this.jdbc = jdbc;
		this.chainVerifier = chainVerifier;
		this.runs = runs;
		this.holds = holds;
		this.dbClock = dbClock;
		this.currencies = currencies;
		this.oneSnapshot = readOnlySnapshot(tx);
		this.newTransaction = new TransactionTemplate(tx);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		discrepancyQueries.put(CheckCode.TRIAL_BALANCE, sqlFile("trial_balance"));
		discrepancyQueries.put(CheckCode.TRANSACTION_BALANCE, sqlFile("transaction_balance"));
		discrepancyQueries.put(CheckCode.ACCOUNT_BALANCE, sqlFile("account_balances"));
		this.moneySupplyQuery = sqlFile("money_supply");
	}

	/** What the checks saw, all from one consistent snapshot. */
	private record Findings(Instant startedAt, int accounts, int transactions, List<IntegrityReport.MoneySupply> moneySupply,
			IntegrityReport.Chain chain, List<Discrepancy> discrepancies) {
	}

	public IntegrityReport run() {
		// 1. Every check in ONE repeatable-read snapshot, so they all describe the same moment.
		Findings found = oneSnapshot.execute(status -> check());
		// 2. Store the run and what it found, in its own transaction.
		ReconciliationRun run = new ReconciliationRun(UUID.randomUUID(), ReconciliationRun.Kind.INTEGRITY, null, false,
				found.startedAt(), dbClock.now(), found.accounts(), found.transactions(), found.discrepancies().size());
		newTransaction.executeWithoutResult(status -> runs.save(run, found.discrepancies()));
		// 3. Anything wrong stops the writes.
		if (!found.discrepancies().isEmpty()) {
			holds.hold("integrity run " + run.id() + " found " + found.discrepancies().size() + " discrepancies");
		}
		return IntegrityReport.of(run, found.moneySupply(), found.chain(), null, 0, found.discrepancies(),
				holds.status().held());
	}

	/** A stored run (either kind). The live-only parts (money supply, chain, accounts) are null. */
	public IntegrityReport storedRun(UUID runId) {
		ReconciliationRun run = runs.findRun(runId).orElseThrow(() -> LedgerException.runNotFound(runId));
		return IntegrityReport.of(run, null, null, null, runs.countSnapshots(runId), runs.findDiscrepancies(runId),
				holds.status().held());
	}

	private Findings check() {
		Instant startedAt = dbClock.now();
		List<Discrepancy> discrepancies = new ArrayList<>();
		discrepancyQueries.forEach((code, sql) -> discrepancies.addAll(jdbc.query(sql, discrepancyRow(code))));

		List<IntegrityReport.MoneySupply> moneySupply = new ArrayList<>();
		for (Discrepancy row : jdbc.query(moneySupplyQuery, discrepancyRow(CheckCode.MONEY_SUPPLY))) {
			boolean matches = row.expected().equals(row.actual());
			moneySupply.add(new IntegrityReport.MoneySupply(row.currency(),
					Money.of(row.expected(), row.currency(), currencies), Money.of(row.actual(), row.currency(), currencies),
					matches));
			if (!matches) {
				discrepancies.add(row);
			}
		}

		List<Discrepancy> chainProblems = chainVerifier.verify();
		discrepancies.addAll(chainProblems);
		Map<String, Object> head = jdbc.queryForMap("select chain_length, head_hash from ledger_state where id = 1");
		IntegrityReport.Chain chain = new IntegrityReport.Chain((Long) head.get("chain_length"),
				(String) head.get("head_hash"), chainProblems.isEmpty());

		int accounts = jdbc.queryForObject("select count(*) from accounts", Integer.class);
		int transactions = jdbc.queryForObject("select count(*) from journal_transactions", Integer.class);
		return new Findings(startedAt, accounts, transactions, moneySupply, chain, discrepancies);
	}

	/** Every check file returns: account_id, transaction_id, currency, expected, actual, detail. */
	private static RowMapper<Discrepancy> discrepancyRow(CheckCode code) {
		return (rs, row) -> new Discrepancy(code, rs.getObject("account_id", UUID.class),
				rs.getObject("transaction_id", UUID.class), rs.getString("currency"), rs.getObject("expected", Long.class),
				rs.getObject("actual", Long.class), rs.getString("detail"));
	}

	static TransactionTemplate readOnlySnapshot(PlatformTransactionManager tx) {
		TransactionTemplate template = new TransactionTemplate(tx);
		template.setReadOnly(true);
		template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		return template;
	}

	static String sqlFile(String name) {
		try {
			return new ClassPathResource("sql/checks/" + name + ".sql").getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
