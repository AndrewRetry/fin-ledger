package com.andrewretry.ledger.integrity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** One stored run of the integrity checks or of a month-end close. {@code period} is the month's first day. */
public record ReconciliationRun(UUID id, Kind kind, LocalDate period, boolean provisional, Instant startedAt,
		Instant finishedAt, int checkedAccounts, int checkedTransactions, int discrepancyCount) {

	/** The two kinds of run, each with the checks it reports on. */
	public enum Kind {
		INTEGRITY(List.of(CheckCode.TRIAL_BALANCE, CheckCode.TRANSACTION_BALANCE, CheckCode.ACCOUNT_BALANCE,
				CheckCode.MONEY_SUPPLY, CheckCode.CHAIN_LINK_BROKEN, CheckCode.CHAIN_TX_HASH_MISMATCH,
				CheckCode.CHAIN_HEAD_MISMATCH)),
		MONTH_END(List.of(CheckCode.MONTH_END_CLOSING_MISMATCH, CheckCode.MONTH_END_OPENING_MISMATCH,
				CheckCode.MONTH_END_SNAPSHOT_MISMATCH));

		private final List<CheckCode> checks;

		Kind(List<CheckCode> checks) {
			this.checks = checks;
		}

		public List<CheckCode> checks() {
			return checks;
		}
	}

	public String status() {
		return discrepancyCount == 0 ? "CLEAN" : "DISCREPANCIES";
	}

}
