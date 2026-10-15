package com.andrewretry.ledger.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

	@Test
	void typeIsALowerCaseDashedUri() {
		assertThat(ErrorCode.ACCOUNT_NOT_FOUND.type()).isEqualTo("https://ledger.example/errors/account-not-found");
	}

	@Test
	void statusesFollowTheErrorTable() {
		assertThat(ErrorCode.VALIDATION_FAILED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(ErrorCode.ALREADY_REVERSED.status()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(ErrorCode.INSUFFICIENT_FUNDS.status().value()).isEqualTo(422);
		assertThat(ErrorCode.INVARIANT_VIOLATION.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(ErrorCode.TRY_AGAIN.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void everyCodeHasATitle() {
		for (ErrorCode code : ErrorCode.values()) {
			assertThat(code.title()).as(code.name()).isNotBlank();
		}
	}

}
