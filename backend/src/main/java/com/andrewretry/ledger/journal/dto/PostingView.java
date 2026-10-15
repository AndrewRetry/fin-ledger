package com.andrewretry.ledger.journal.dto;

import java.util.UUID;

import com.andrewretry.ledger.common.Money;
import com.andrewretry.ledger.journal.Direction;

public record PostingView(UUID accountId, Direction direction, Money amount, Money balanceAfter, long accountVersion) {
}
