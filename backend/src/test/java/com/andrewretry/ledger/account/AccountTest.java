package com.andrewretry.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.andrewretry.ledger.common.error.ErrorCode;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.journal.AppliedPosting;
import com.andrewretry.ledger.journal.Direction;

class AccountTest {

	private final Account account = new Account("Alice", "SGD", Instant.EPOCH);

	@Test
	void creditAddsAndRecordsTheNewVersionAndBalance() {
		AppliedPosting posting = account.apply(Direction.CREDIT, 2500);

		assertThat(posting).isEqualTo(new AppliedPosting(account.getId(), "SGD", Direction.CREDIT, 2500, 1, 2500));
		assertThat(account.getBalance()).isEqualTo(2500);
		assertThat(account.getVersion()).isEqualTo(1);
	}

	@Test
	void debitSubtracts() {
		account.apply(Direction.CREDIT, 2500);

		AppliedPosting posting = account.apply(Direction.DEBIT, 1000);

		assertThat(posting.balanceAfter()).isEqualTo(1500);
		assertThat(posting.accountVersion()).isEqualTo(2);
	}

	@Test
	void refusesToTakeACustomerBelowZeroAndChangesNothing() {
		account.apply(Direction.CREDIT, 100);

		assertThatThrownBy(() -> account.apply(Direction.DEBIT, 101))
				.isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
		assertThat(account.getBalance()).isEqualTo(100);
		assertThat(account.getVersion()).isEqualTo(1);
	}

	@Test
	void throwsOnOverflowInsteadOfWrappingAround() {
		account.apply(Direction.CREDIT, Long.MAX_VALUE);

		assertThatThrownBy(() -> account.apply(Direction.CREDIT, 1)).isInstanceOf(ArithmeticException.class);
		assertThat(account.getBalance()).isEqualTo(Long.MAX_VALUE);
	}

}
