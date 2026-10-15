package com.andrewretry.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class MoneyTest {

	private final Currencies currencies = new Currencies(Map.of("SGD", 2, "JPY", 0));

	@Test
	void displaysTwoDecimalCurrencyWithCents() {
		assertThat(Money.of(2500, "SGD", currencies)).isEqualTo(new Money(2500, "SGD", "25.00"));
	}

	@Test
	void displaysZeroDecimalCurrencyWithoutFraction() {
		assertThat(Money.of(1500, "JPY", currencies).display()).isEqualTo("1500");
	}

	@Test
	void displaysNegativeAmounts() {
		assertThat(Money.of(-100000, "SGD", currencies).display()).isEqualTo("-1000.00");
	}

	@Test
	void displaysAmountsBelowOneMajorUnit() {
		assertThat(Money.of(5, "SGD", currencies).display()).isEqualTo("0.05");
		assertThat(Money.of(0, "SGD", currencies).display()).isEqualTo("0.00");
	}

	@Test
	void rejectsUnsupportedCurrency() {
		assertThatThrownBy(() -> Money.of(1, "XXX", currencies)).isInstanceOf(IllegalArgumentException.class);
	}

}
