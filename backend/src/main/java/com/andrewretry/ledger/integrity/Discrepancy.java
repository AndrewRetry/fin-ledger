package com.andrewretry.ledger.integrity;

import java.util.UUID;

/** One thing a check found wrong. {@code accountId}, {@code transactionId} and the numbers are null when not relevant. */
public record Discrepancy(CheckCode checkCode, UUID accountId, UUID transactionId, String currency, Long expected,
		Long actual, String detail) {
}
