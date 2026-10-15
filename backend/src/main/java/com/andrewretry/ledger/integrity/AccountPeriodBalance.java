package com.andrewretry.ledger.integrity;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One account's frozen month-end snapshot, written once by the first clean, final close of the month.
 * Next month's opening must equal {@code closing}; re-closing this month must still land on it.
 */
public record AccountPeriodBalance(UUID accountId, LocalDate period, String currency, long opening, long debits,
		long credits, long closing, long lastAccountVersion) {
}
