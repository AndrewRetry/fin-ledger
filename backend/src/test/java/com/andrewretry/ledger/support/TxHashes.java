package com.andrewretry.ledger.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.andrewretry.ledger.journal.AppliedPosting;
import com.andrewretry.ledger.journal.Direction;
import com.andrewretry.ledger.journal.TransactionType;
import com.andrewretry.ledger.journal.TxHasher;

import tools.jackson.databind.JsonNode;

/** Rebuilds the hash input from a transaction's JSON: an auditor can check any tx_hash from the API alone. */
public final class TxHashes {

	private TxHashes() {
	}

	public static TxHasher.Input inputOf(JsonNode tx) {
		List<AppliedPosting> postings = new ArrayList<>();
		for (JsonNode p : tx.path("postings")) {
			postings.add(new AppliedPosting(UUID.fromString(p.path("accountId").asString()),
					p.path("amount").path("currency").asString(), Direction.valueOf(p.path("direction").asString()),
					p.path("amount").path("amount").asLong(), p.path("accountVersion").asLong(),
					p.path("balanceAfter").path("amount").asLong()));
		}
		return new TxHasher.Input(tx.path("chainIndex").asLong(), tx.path("prevHash").asString(),
				UUID.fromString(tx.path("id").asString()), TransactionType.valueOf(tx.path("type").asString()),
				Instant.parse(tx.path("createdAt").asString()), tx.path("idempotencyKey").asString(),
				textOrNull(tx, "externalRef"), uuidOrNull(tx, "reversesId"),
				tx.path("fx").isNull() ? null : UUID.fromString(tx.path("fx").path("rateId").asString()),
				textOrNull(tx, "description"),
				postings);
	}

	private static String textOrNull(JsonNode tx, String field) {
		return tx.path(field).isNull() ? null : tx.path(field).asString();
	}

	private static UUID uuidOrNull(JsonNode tx, String field) {
		return tx.path(field).isNull() ? null : UUID.fromString(tx.path(field).asString());
	}

}
