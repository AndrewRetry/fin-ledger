package com.andrewretry.ledger.journal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.andrewretry.ledger.common.Canonical;

/** tx_hash = SHA-256 of the canonical encoding in PLAN.md §7.6. Appendix A holds the test vectors. */
public final class TxHasher {

	/** Everything that is hashed. Absent values are null here and "" in the encoding. */
	public record Input(long chainIndex, String prevHash, UUID id, TransactionType type, Instant createdAt,
			String idempotencyKey, String externalRef, UUID reversesId, UUID fxRateId, String description,
			List<AppliedPosting> postings) {
	}

	private TxHasher() {
	}

	public static String hash(Input tx) {
		return canonical(tx).sha256Hex();
	}

	static Canonical canonical(Input tx) {
		Canonical c = new Canonical()
				.field("LEDGER-TX-V2")
				.field(tx.chainIndex())
				.field(tx.prevHash())
				.field(tx.id().toString())
				.field(tx.type().name())
				.field(Canonical.micros(tx.createdAt()))
				.field(tx.idempotencyKey())
				.field(orEmpty(tx.externalRef()))
				.field(orEmpty(tx.reversesId()))
				.field(orEmpty(tx.fxRateId()))
				.field(orEmpty(tx.description()))
				.field(tx.postings().size());
		tx.postings().stream().sorted(AppliedPosting.BY_ACCOUNT_ID).forEach(p -> c
				.field(p.accountId().toString())
				.field(p.currency())
				.field(p.direction().name())
				.field(p.amount())
				.field(p.accountVersion())
				.field(p.balanceAfter()));
		return c;
	}

	private static String orEmpty(Object value) {
		return Objects.toString(value, "");
	}

}
