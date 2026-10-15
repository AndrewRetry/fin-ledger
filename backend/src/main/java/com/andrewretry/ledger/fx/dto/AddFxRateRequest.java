package com.andrewretry.ledger.fx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code rate} is a decimal string, e.g. "0.7812": at most 12 digits before and 12 after the point. */
public record AddFxRateRequest(
		@NotBlank String base,
		@NotBlank String quote,
		@NotBlank @Pattern(regexp = "\\d{1,12}(\\.\\d{1,12})?", message = "must be a decimal like 0.7812 with at most 12 decimals")
		String rate) {
}
