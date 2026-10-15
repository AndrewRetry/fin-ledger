package com.andrewretry.ledger.journal.dto;

import java.util.UUID;

import com.andrewretry.ledger.common.Text;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code amount} is in minor units of the source currency; {@code currency}, if sent, must be that currency. */
public record TransferRequest(
		@Schema(example = "8398f93a-24c5-3661-8e9a-33d54273d824") @NotNull UUID sourceAccountId,
		@Schema(example = "9f75da44-22c8-31e4-9a7d-2bd07518cc4a") @NotNull UUID destinationAccountId,
		@Schema(example = "2500") @NotNull @Min(1) @Max(1_000_000_000_000L) Long amount,
		@Schema(example = "SGD") String currency,
		@Schema(example = "Lunch split") @Size(max = 280) String description) {

	/** Trimmed before validation, so "  SGD " is SGD and "" means absent. */
	public TransferRequest {
		currency = Text.clean(currency);
		description = Text.clean(description);
	}

}
