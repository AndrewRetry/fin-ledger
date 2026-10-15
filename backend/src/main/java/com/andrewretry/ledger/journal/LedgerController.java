package com.andrewretry.ledger.journal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.andrewretry.ledger.journal.dto.LedgerHead;

@RestController
public class LedgerController {

	private final TransactionQuery transactions;

	public LedgerController(TransactionQuery transactions) {
		this.transactions = transactions;
	}

	@GetMapping("/ledger/head")
	LedgerHead head() {
		return transactions.head();
	}

}
