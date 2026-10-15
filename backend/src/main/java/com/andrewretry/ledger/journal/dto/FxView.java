package com.andrewretry.ledger.journal.dto;

import java.util.UUID;

import com.andrewretry.ledger.fx.FxRate;

/** The rate an FX transfer used. Null on every other transaction. */
public record FxView(UUID rateId, String base, String quote, String rate, String source) {

	public static FxView of(FxRate fx) {
		return new FxView(fx.getId(), fx.getBase(), fx.getQuote(), fx.getRate().stripTrailingZeros().toPlainString(),
				fx.getSource());
	}

}
