package com.andrewretry.ledger.journal;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.andrewretry.ledger.common.Currencies;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.journal.dto.LedgerHead;
import com.andrewretry.ledger.journal.dto.TransactionDetails;
import com.andrewretry.ledger.journal.dto.TransactionView;

/** Read side of the journal. */
@Service
public class TransactionQuery {

	private final JournalTransactionRepository journal;
	private final LedgerStateRepository ledgerState;
	private final Currencies currencies;

	public TransactionQuery(JournalTransactionRepository journal, LedgerStateRepository ledgerState,
			Currencies currencies) {
		this.journal = journal;
		this.ledgerState = ledgerState;
		this.currencies = currencies;
	}

	@Transactional(readOnly = true)
	public TransactionDetails get(UUID id) {
		JournalTransaction tx = journal.findById(id).orElseThrow(() -> LedgerException.transactionNotFound(id));
		return new TransactionDetails(TransactionView.of(tx, currencies), journal.findReversalIdOf(id).orElse(null));
	}

	@Transactional(readOnly = true)
	public LedgerHead head() {
		LedgerState head = ledgerState.head();
		return new LedgerHead(head.getChainLength(), head.getHeadHash());
	}

}
