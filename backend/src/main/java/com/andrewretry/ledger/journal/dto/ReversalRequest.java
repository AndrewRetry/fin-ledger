package com.andrewretry.ledger.journal.dto;

import com.andrewretry.ledger.common.Text;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why the transaction is being reversed; stored as the reversal's description. */
public record ReversalRequest(@Schema(example = "Customer dispute") @NotBlank @Size(max = 280) String reason) {

	/** Trimmed before validation: 1-280 characters after trimming. */
	public ReversalRequest {
		reason = Text.clean(reason);
	}

}
