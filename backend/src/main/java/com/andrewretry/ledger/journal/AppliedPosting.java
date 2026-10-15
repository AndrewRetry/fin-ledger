package com.andrewretry.ledger.journal;

import java.util.Comparator;
import java.util.UUID;

/** One posting after it was applied to its locked account: what the posting row and the hash record. */
public record AppliedPosting(UUID accountId, String currency, Direction direction, long amount, long accountVersion,
		long balanceAfter) {

	/**
	 * Postings are always ordered by account id as a string. That is PostgreSQL's uuid order too; Java's
	 * {@code UUID.compareTo} is NOT (it compares signed longs), so never sort UUIDs directly.
	 */
	public static final Comparator<AppliedPosting> BY_ACCOUNT_ID = Comparator.comparing(p -> p.accountId().toString());

}
