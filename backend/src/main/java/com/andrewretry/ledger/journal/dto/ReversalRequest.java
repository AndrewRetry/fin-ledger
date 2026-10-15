package com.andrewretry.ledger.journal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why the transaction is being reversed; stored as the reversal's description. */
public record ReversalRequest(@NotBlank @Size(max = 280) String reason) {
}
