package com.andrewretry.ledger.integrity;

/** What a discrepancy is about. Stored as text in reconciliation_discrepancies.check_code. */
public enum CheckCode {
	/** Per currency: total debits = total credits, and all balances sum to zero. */
	TRIAL_BALANCE,
	/** Every transaction has at least 2 postings and balances per currency. */
	TRANSACTION_BALANCE,
	/** Every account's cached balance and version agree with its postings. */
	ACCOUNT_BALANCE,
	/** Money held in wallets = net money deposited from outside, per currency. */
	MONEY_SUPPLY,
	/** A transaction's chain index or prev_hash does not follow the previous one. */
	CHAIN_LINK_BROKEN,
	/** A transaction's stored tx_hash is not the hash of its own contents: it was edited. */
	CHAIN_TX_HASH_MISMATCH,
	/** ledger_state does not point at the newest transaction (e.g. the newest one was deleted). */
	CHAIN_HEAD_MISMATCH,
	/** Opening + the month's credits - debits does not land on the recorded closing balance. */
	MONTH_END_CLOSING_MISMATCH,
	/** This month's opening is not last month's frozen closing. */
	MONTH_END_OPENING_MISMATCH,
	/** A closed month no longer matches the snapshot frozen when it was closed. */
	MONTH_END_SNAPSHOT_MISMATCH
}
