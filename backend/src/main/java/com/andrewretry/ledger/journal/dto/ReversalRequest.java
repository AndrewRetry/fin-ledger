package com.andrewretry.ledger.journal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why the transaction is being reversed; stored as the reversal's description. */
public record ReversalRequest(@Schema(example = "Customer dispute") @NotBlank @Size(max = 280) String reason) {
}
