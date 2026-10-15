package com.andrewretry.ledger.fx.dto;

import java.time.Instant;
import java.util.UUID;

import com.andrewretry.ledger.fx.FxRate;

/** {@code rate} is a string so no decimal is ever lost to a JSON double. */
public record FxRateResponse(UUID id, String base, String quote, String rate, String source, Instant createdAt) {

	public static FxRateResponse of(FxRate fx) {
		return new FxRateResponse(fx.getId(), fx.getBase(), fx.getQuote(), fx.getRate().stripTrailingZeros().toPlainString(),
				fx.getSource(), fx.getCreatedAt());
	}

}
