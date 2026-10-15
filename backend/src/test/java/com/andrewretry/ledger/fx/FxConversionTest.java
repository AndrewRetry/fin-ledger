package com.andrewretry.ledger.fx;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** PLAN.md Appendix A: FX vectors. */
class FxConversionTest {

	@ParameterizedTest(name = "{0} at {1} ({2} -> {3} decimals) = {4}")
	@CsvSource({
			"10000,  0.775,     2, 2, 7750",    // SGD 100.00 -> USD 77.50, the seeded rate
			"999,    151.51515, 2, 0, 1513",    // USD 9.99 -> JPY, floor of 1513.636...
			"1,      0.0066,    0, 2, 0",       // JPY 1 -> USD: too small, becomes FX_AMOUNT_TOO_SMALL
			"12345,  1.4967742, 2, 2, 18477",   // EUR 123.45 -> SGD 184.77, floor
			"10000,  151.234567, 2, 0, 15123",  // USD 100.00 -> JPY, floor of 15123.4567
			"250000, 0.0066118, 0, 2, 165295",  // JPY 250,000 -> USD 1,652.95, exact
			"12345,  0.68123,   2, 2, 8409"     // SGD 123.45 -> EUR 84.09, floor of 8409.78...
	})
	void convertsAndRoundsDown(long fromMinor, String rate, int fromUnits, int toUnits, long expected) {
		assertThat(FxConversion.convert(fromMinor, new BigDecimal(rate), fromUnits, toUnits)).isEqualTo(expected);
	}

}
