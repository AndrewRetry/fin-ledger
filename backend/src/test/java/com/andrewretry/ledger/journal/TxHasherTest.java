package com.andrewretry.ledger.journal;

import static com.andrewretry.ledger.journal.Direction.CREDIT;
import static com.andrewretry.ledger.journal.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** PLAN.md Appendix A: vectors agreed by an independent Python implementation. */
class TxHasherTest {

	static UUID id(String suffix) {
		return UUID.fromString("0192f0a0-0000-7000-8000-" + "0".repeat(12 - suffix.length()) + suffix);
	}

	static final UUID A = id("a"), B = id("b"), FXLIQ_SGD = id("c"), FXLIQ_USD = id("d"), C = id("e"), FUNDING_SGD = id("f");
	static final String ZEROS = "0".repeat(64);

	static TxHasher.Input tx1() {
		return new TxHasher.Input(1, ZEROS, id("1"), TransactionType.TRANSFER, Instant.parse("2026-10-14T03:00:00.123456Z"),
				"demo-key-1", null, null, null, "Lunch split",
				List.of(new AppliedPosting(B, "SGD", CREDIT, 2500, 1, 2500),      // B first on purpose: must be sorted
						new AppliedPosting(A, "SGD", DEBIT, 2500, 3, 7500)));
	}

	@Test
	void encodesTx1Canonically() {
		byte[] bytes = TxHasher.canonical(tx1()).bytes();

		assertThat(bytes).hasSize(362);
		assertThat(HexFormat.of().formatHex(bytes))
				.startsWith("0000000c4c45444745522d54582d5632000000013100000040303030303030303030303030303030");
	}

	@Test
	void hashesTheFourChainedVectors() {
		String h1 = TxHasher.hash(tx1());
		assertThat(h1).isEqualTo("5fc81c8d533a218242da8ff6709fb74924c8fcba6f91dc6a713f5defb53194ae");

		String h2 = TxHasher.hash(new TxHasher.Input(2, h1, id("2"), TransactionType.REVERSAL,
				Instant.parse("2026-10-14T03:00:01Z"), "demo-key-2", null, id("1"), null, "Entered twice",
				List.of(new AppliedPosting(A, "SGD", CREDIT, 2500, 4, 10000),
						new AppliedPosting(B, "SGD", DEBIT, 2500, 2, 0))));
		assertThat(h2).isEqualTo("989d8af316373cf36472b1692966dcac1103885a6abc6be5ddd8cde877e8b815");

		String h3 = TxHasher.hash(new TxHasher.Input(3, h2, id("3"), TransactionType.FX_TRANSFER,
				Instant.parse("2026-10-14T03:00:02.5Z"), "demo-key-3", null, null, id("f1"), null,
				List.of(new AppliedPosting(C, "USD", CREDIT, 7750, 1, 7750),
						new AppliedPosting(FXLIQ_USD, "USD", DEBIT, 7750, 2, 99992250),
						new AppliedPosting(A, "SGD", DEBIT, 10000, 5, 0),
						new AppliedPosting(FXLIQ_SGD, "SGD", CREDIT, 10000, 1, 10000))));
		assertThat(h3).isEqualTo("d9e3f0b83c2999e3c1984ba4ea9de3155fc2602bcea1f16c7077fda34f14c32d");

		String h4 = TxHasher.hash(new TxHasher.Input(4, h3, id("4"), TransactionType.DEPOSIT,
				Instant.parse("2026-10-14T03:00:03Z"), "demo-key-4", "fb-tx-1001", null, null, "Fireblocks deposit",
				List.of(new AppliedPosting(FUNDING_SGD, "SGD", DEBIT, 50000, 7, -150000),
						new AppliedPosting(A, "SGD", CREDIT, 50000, 6, 50000))));
		assertThat(h4).isEqualTo("883c8817a5a05ec62999d501b93e5f1638adbf86f08e60ec934c619920cee9c1");
	}

	/** The vectors only differ in the last character, so they can't catch a signed UUID.compareTo sort. */
	@Test
	void sortsPostingsByAccountIdAsText() {
		UUID low = UUID.fromString("0fffffff-0000-0000-0000-000000000000");
		UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
		assertThat(high.compareTo(low)).isNegative(); // the trap: Java puts "8..." before "0..."

		List<AppliedPosting> postings = List.of(new AppliedPosting(high, "SGD", CREDIT, 1, 1, 1),
				new AppliedPosting(low, "SGD", DEBIT, 1, 1, 0));

		assertThat(postings.stream().sorted(AppliedPosting.BY_ACCOUNT_ID).map(AppliedPosting::accountId))
				.containsExactly(low, high);
	}

}
