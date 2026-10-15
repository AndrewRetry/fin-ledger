package com.andrewretry.ledger.journal.dto;

import java.util.UUID;

import com.andrewretry.ledger.common.Text;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /deposits and POST /withdrawals. {@code externalRef} is the outside system's id for the movement. */
public record MoneyMovementRequest(
		@Schema(example = "413efdce-9786-318b-be29-69819a82091a") @NotNull UUID accountId,
		@Schema(example = "10000") @NotNull @Min(1) @Max(1_000_000_000_000L) Long amount,
		@Schema(example = "Top-up") @Size(max = 280) String description,
		@Schema(example = "fb-tx-1001") @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", message = "must be 1-128 characters of letters, digits and . _ : -")
		String externalRef) {

	/** Trimmed before validation, so " fb-tx-1 " is fb-tx-1 and "" means no external reference. */
	public MoneyMovementRequest {
		description = Text.clean(description);
		externalRef = Text.clean(externalRef);
	}

}
