package com.andrewretry.ledger.common;

import java.math.BigDecimal;

/** An amount in minor units ("cents") with its currency and a human-readable form. */
public record Money(long amount, String currency, String display) {

	public static Money of(long amount, String currency, Currencies currencies) {
		return new Money(amount, currency,
				BigDecimal.valueOf(amount, currencies.minorUnits(currency)).toPlainString());
	}

}
