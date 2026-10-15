package com.andrewretry.ledger.journal;

/** Must match the CHECK on journal_transactions.type. */
public enum TransactionType {
	DEPOSIT, WITHDRAWAL, TRANSFER, FX_TRANSFER, REVERSAL
}
