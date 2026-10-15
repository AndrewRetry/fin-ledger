package com.andrewretry.ledger.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Converts minor units of one currency into minor units of another. Exact maths, then rounded DOWN. */
public final class FxConversion {

	private FxConversion() {
	}

	/**
	 * Example: USD 9.99 to JPY at 151.51515 is 999 x 151.51515 = 151363.63 "USD cents worth of yen", moved from
	 * 2 decimals to 0 (divide by 100) = 1513.63 yen, floored to 1513. The fraction stays with the house, so nobody
	 * can gain from rounding by splitting a transfer into many small ones.
	 */
	public static long convert(long fromMinor, BigDecimal rate, int fromMinorUnits, int toMinorUnits) {
		return BigDecimal.valueOf(fromMinor)
				.multiply(rate)
				.scaleByPowerOfTen(toMinorUnits - fromMinorUnits)
				.setScale(0, RoundingMode.FLOOR)
				.longValueExact();
	}

}
