package com.andrewretry.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.andrewretry.ledger.common.error.ErrorCode;
import com.andrewretry.ledger.common.error.LedgerException;

class IdempotencyKeysTest {

	@ParameterizedTest
	@ValueSource(strings = { "a", "demo-key-1", "seed-fxliq-SGD", "0192f0a0-0000-7000-8000-000000000001", "a.b_c:d-e" })
	void acceptsValidKeys(String key) {
		assertThat(IdempotencyKeys.parse(key)).isEqualTo(key);
	}

	@Test
	void stripsOnePairOfQuotes() {
		assertThat(IdempotencyKeys.parse("\"demo-key-1\"")).isEqualTo("demo-key-1");
	}

	@Test
	void acceptsTheLongestKey() {
		String key = "k".repeat(255);
		assertThat(IdempotencyKeys.parse(key)).isEqualTo(key);
	}

	@ParameterizedTest
	@ValueSource(strings = { "-starts-with-dash", "has space", "ключ", "a/b", "\"\"" })
	void rejectsMalformedKeys(String key) {
		assertThatThrownBy(() -> IdempotencyKeys.parse(key))
				.isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
	}

	@Test
	void rejectsAKeyLongerThan255() {
		assertThatThrownBy(() -> IdempotencyKeys.parse("k".repeat(256))).isInstanceOf(LedgerException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void treatsABlankHeaderAsMissing(String header) {
		assertThatThrownBy(() -> IdempotencyKeys.parse(header))
				.isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISSING));
	}

	@Test
	void treatsANullHeaderAsMissing() {
		assertThatThrownBy(() -> IdempotencyKeys.parse(null))
				.isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISSING));
	}

}
