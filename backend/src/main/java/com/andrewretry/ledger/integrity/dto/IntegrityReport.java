package com.andrewretry.ledger.integrity.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.andrewretry.ledger.common.Money;
import com.andrewretry.ledger.integrity.CheckCode;
import com.andrewretry.ledger.integrity.Discrepancy;
import com.andrewretry.ledger.integrity.ReconciliationRun;

/**
 * The answer of an integrity run or a month-end close (PLAN.md §8.2). Parts that only one kind has are null for
 * the other, and for a stored run read back later.
 */
public record IntegrityReport(UUID runId, ReconciliationRun.Kind kind, String period, boolean provisional,
		String status, Instant startedAt, Instant finishedAt, int checkedAccounts, int checkedTransactions,
		List<MoneySupply> moneySupply, Chain chain, List<AccountLine> accounts, int snapshotsWritten,
		List<Check> checks, List<Discrepancy> discrepancies, boolean integrityHold) {

	/** Per currency: net money deposited from outside (expected) vs money held in wallets (actual). */
	public record MoneySupply(String currency, Money expected, Money actual, boolean matches) {
	}

	public record Chain(long length, String headHash, boolean valid) {
	}

	/** Month-end, per account: opening + credits - debits should equal the recorded closing. */
	public record AccountLine(UUID accountId, String currency, Money opening, Money debits, Money credits,
			Money expectedClosing, Money actualClosing, boolean matches) {
	}

	/** How many discrepancies one check found. */
	public record Check(CheckCode code, long discrepancies) {
	}

	public static IntegrityReport of(ReconciliationRun run, List<MoneySupply> moneySupply, Chain chain,
			List<AccountLine> accounts, int snapshotsWritten, List<Discrepancy> discrepancies, boolean integrityHold) {
		List<Check> checks = run.kind().checks().stream()
				.map(code -> new Check(code, discrepancies.stream().filter(d -> d.checkCode() == code).count()))
				.toList();
		return new IntegrityReport(run.id(), run.kind(), run.period() == null ? null : run.period().toString().substring(0, 7),
				run.provisional(), run.status(), run.startedAt(), run.finishedAt(), run.checkedAccounts(),
				run.checkedTransactions(), moneySupply, chain, accounts, snapshotsWritten, checks, discrepancies,
				integrityHold);
	}

}
