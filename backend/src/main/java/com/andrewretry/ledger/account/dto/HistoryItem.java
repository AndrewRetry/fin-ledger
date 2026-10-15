package com.andrewretry.ledger.account.dto;

import java.time.Instant;
import java.util.UUID;

import com.andrewretry.ledger.common.Money;
import com.andrewretry.ledger.journal.Direction;
import com.andrewretry.ledger.journal.TransactionType;

/** One posting of the account, with the account's balance right after it. */
public record HistoryItem(UUID transactionId, TransactionType type, Direction direction, Money amount,
		Money balanceAfter, long accountVersion, Instant createdAt, String description) {
}
