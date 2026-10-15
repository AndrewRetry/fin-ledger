package com.andrewretry.ledger.integrity.dto;

import java.time.Instant;

/** The integrity hold: while {@code held}, money-moving requests get 503. */
public record HoldStatus(boolean held, String reason, Instant since) {
}
