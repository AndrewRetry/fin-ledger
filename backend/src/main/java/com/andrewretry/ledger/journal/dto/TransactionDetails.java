package com.andrewretry.ledger.journal.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** GET /transactions/{id}: the POST body plus {@code reversedById}, which POST bodies leave out so replays never change. */
public record TransactionDetails(@JsonUnwrapped TransactionView transaction, UUID reversedById) {
}
