package com.andrewretry.ledger.integrity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why the administrator is releasing the hold; logged. */
public record ReleaseHoldRequest(@Schema(example = "Checked: discrepancy explained and fixed") @NotBlank @Size(max = 500) String reason) {
}
