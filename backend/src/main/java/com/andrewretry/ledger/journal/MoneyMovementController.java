package com.andrewretry.ledger.journal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.andrewretry.ledger.journal.dto.MoneyMovementRequest;
import com.andrewretry.ledger.journal.dto.TransactionView;

import jakarta.validation.Valid;

/** Money in from and out to the outside world. */
@RestController
public class MoneyMovementController {

	private final LedgerCommandService commands;

	public MoneyMovementController(LedgerCommandService commands) {
		this.commands = commands;
	}

	@PostMapping("/deposits")
	ResponseEntity<TransactionView> deposit(@RequestHeader(name = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody MoneyMovementRequest request) {
		return commands.deposit(IdempotencyKeys.parse(key), request.accountId(), request.amount(), request.description(),
				request.externalRef()).toCreatedResponse();
	}

	@PostMapping("/withdrawals")
	ResponseEntity<TransactionView> withdraw(@RequestHeader(name = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody MoneyMovementRequest request) {
		return commands.withdraw(IdempotencyKeys.parse(key), request.accountId(), request.amount(),
				request.description(), request.externalRef()).toCreatedResponse();
	}

}
