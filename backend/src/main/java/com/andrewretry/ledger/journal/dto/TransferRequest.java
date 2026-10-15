package com.andrewretry.ledger.journal.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code amount} is in minor units of the source currency; {@code currency}, if sent, must be that currency. */
public record TransferRequest(
		@NotNull UUID sourceAccountId,
		@NotNull UUID destinationAccountId,
		@NotNull @Min(1) @Max(1_000_000_000_000L) Long amount,
		String currency,
		@Size(max = 280) String description) {
}
