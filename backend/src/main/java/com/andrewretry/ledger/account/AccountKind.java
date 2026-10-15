package com.andrewretry.ledger.account;

/** What an account is for. Every kind follows the same rule: CREDIT = money in, DEBIT = money out. */
public enum AccountKind {
	/** A customer wallet; balance never below 0. */
	CUSTOMER,
	/** The house's wallet per currency, used to convert FX; balance never below 0. */
	FX_LIQUIDITY,
	/** The outside world per currency; balance never above 0 (minus the net money deposited). */
	FUNDING
}
