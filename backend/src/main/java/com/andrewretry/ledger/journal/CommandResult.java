package com.andrewretry.ledger.journal;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.andrewretry.ledger.journal.dto.TransactionView;

/** What a command returns: the transaction, and whether it was an earlier one replayed for a retried request. */
public record CommandResult(TransactionView view, boolean replayed) {

	public static final String REPLAYED_HEADER = "Idempotent-Replayed";

	/** 201 for both a new transaction and a replay; a replay is marked with {@code Idempotent-Replayed: true}. */
	public ResponseEntity<TransactionView> toCreatedResponse() {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED)
				.location(URI.create("/transactions/" + view.id()));
		if (replayed) {
			response.header(REPLAYED_HEADER, "true");
		}
		return response.body(view);
	}

}
