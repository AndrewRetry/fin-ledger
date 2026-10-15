package com.andrewretry.ledger.journal.dto;

import java.util.UUID;

/** The rate an FX transfer used. Null on every other transaction. */
public record FxView(UUID rateId, String base, String quote, String rate, String source) {
}
