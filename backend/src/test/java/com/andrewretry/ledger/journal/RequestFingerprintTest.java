package com.andrewretry.ledger.journal;

import static com.andrewretry.ledger.journal.TxHasherTest.A;
import static com.andrewretry.ledger.journal.TxHasherTest.B;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

	@Test
	void fingerprintsATransferWithAllFields() {
		assertThat(RequestFingerprint.transfer(A, B, 2500, "SGD", "Lunch split"))
				.isEqualTo("f6ee2bbbdf0fcf371903d4f2a3b64ae234e9d2f40441165a9526a9a939d786fe");
	}

	@Test
	void fingerprintsAbsentOptionalFieldsAsEmpty() {
		assertThat(RequestFingerprint.transfer(A, B, 2500, null, null))
				.isEqualTo("a57fc881ac5552c340b3a3f013a006a5be0c334f96baec31c0d27d94ed20ba77");
		assertThat(RequestFingerprint.deposit(A, 10000, null, null))
				.isEqualTo("76bf77d4faa8f850c20269faccc207c6a8207398a63868a141ac2a1a916fb791");
	}

	@Test
	void fingerprintsADepositWithAnExternalReference() {
		assertThat(RequestFingerprint.deposit(A, 50000, "Fireblocks deposit", "fb-tx-1001"))
				.isEqualTo("e5b245ac1c026084b46ffa0f5fda942b71c7deb6aff5f168169a5ed6042f0937");
	}

	@Test
	void distinguishesADepositFromTheSameWithdrawal() {
		assertThat(RequestFingerprint.withdrawal(A, 10000, null, null))
				.isNotEqualTo(RequestFingerprint.deposit(A, 10000, null, null));
	}

}
