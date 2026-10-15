package com.andrewretry.ledger.journal;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.andrewretry.ledger.journal.dto.TransactionDetails;
import com.andrewretry.ledger.journal.dto.TransactionView;
import com.andrewretry.ledger.journal.dto.TransferRequest;

import jakarta.validation.Valid;

@RestController
public class TransactionController {

	private final LedgerCommandService commands;
	private final TransactionQuery transactions;

	public TransactionController(LedgerCommandService commands, TransactionQuery transactions) {
		this.commands = commands;
		this.transactions = transactions;
	}

	@PostMapping("/transactions")
	ResponseEntity<TransactionView> transfer(@RequestHeader(name = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody TransferRequest request) {
		return commands.transfer(IdempotencyKeys.parse(key), request.sourceAccountId(), request.destinationAccountId(),
				request.amount(), request.currency(), request.description()).toCreatedResponse();
	}

	@GetMapping("/transactions/{id}")
	TransactionDetails get(@PathVariable UUID id) {
		return transactions.get(id);
	}

}
