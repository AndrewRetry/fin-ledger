package com.andrewretry.ledger.journal.dto;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

import com.andrewretry.ledger.common.Currencies;
import com.andrewretry.ledger.common.Money;
import com.andrewretry.ledger.fx.FxRate;
import com.andrewretry.ledger.journal.JournalTransaction;
import com.andrewretry.ledger.journal.TransactionType;

/**
 * The response of every money-moving POST. Built only from the stored transaction, never from live account rows,
 * so replaying a request returns exactly the same body, however long afterwards.
 */
public record TransactionView(UUID id, long chainIndex, TransactionType type, String status, String idempotencyKey,
		String externalRef, Instant createdAt, String description, UUID reversesId, FxView fx,
		List<PostingView> postings, String prevHash, String txHash) {

	/** {@code fxRate} is the rate an FX transfer used; null for every other type. */
	public static TransactionView of(JournalTransaction tx, FxRate fxRate, Currencies currencies) {
		List<PostingView> postings = tx.getPostings().stream()
				.map(p -> new PostingView(p.accountId(), p.direction(), Money.of(p.amount(), p.currency(), currencies),
						Money.of(p.balanceAfter(), p.currency(), currencies), p.accountVersion()))
				.toList();
		return new TransactionView(tx.getId(), tx.getChainIndex(), tx.getType(), "POSTED", tx.getIdempotencyKey(),
				tx.getExternalRef(), tx.getCreatedAt(), tx.getDescription(), tx.getReversesId(),
				fxRate == null ? null : FxView.of(fxRate), postings,
				tx.getPrevHash(), tx.getTxHash());
	}

}
