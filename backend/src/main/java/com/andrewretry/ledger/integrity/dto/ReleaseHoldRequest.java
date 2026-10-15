package com.andrewretry.ledger.integrity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why the administrator is releasing the hold; logged. */
public record ReleaseHoldRequest(@NotBlank @Size(max = 500) String reason) {
}
