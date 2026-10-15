package com.andrewretry.ledger.journal.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /deposits and POST /withdrawals. {@code externalRef} is the outside system's id for the movement. */
public record MoneyMovementRequest(
		@NotNull UUID accountId,
		@NotNull @Min(1) @Max(1_000_000_000_000L) Long amount,
		@Size(max = 280) String description,
		@Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", message = "must be 1-128 characters of letters, digits and . _ : -")
		String externalRef) {
}
