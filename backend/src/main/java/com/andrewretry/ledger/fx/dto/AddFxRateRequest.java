package com.andrewretry.ledger.fx.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code rate} is a decimal string, e.g. "0.7812": at most 12 digits before and 12 after the point. */
public record AddFxRateRequest(
		@Schema(example = "SGD") @NotBlank String base,
		@Schema(example = "USD") @NotBlank String quote,
		@Schema(example = "0.7812") @NotBlank @Pattern(regexp = "\\d{1,12}(\\.\\d{1,12})?", message = "must be a decimal like 0.7812 with at most 12 decimals")
		String rate) {
}
