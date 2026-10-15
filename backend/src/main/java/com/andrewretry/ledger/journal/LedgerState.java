package com.andrewretry.ledger.journal;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The single row holding the chain head and the integrity hold. Every write locks it last. */
@Entity
@Table(name = "ledger_state")
public class LedgerState {

	@Id
	private short id;

	@Column(name = "chain_length", nullable = false)
	private long chainLength;

	@Column(name = "head_hash", nullable = false)
	private String headHash;

	@Column(name = "integrity_hold", nullable = false)
	private boolean integrityHold;

	@Column(name = "hold_reason")
	private String holdReason;

	@Column(name = "hold_since")
	private Instant holdSince;

	protected LedgerState() {
	}

	/** Moves the head to the transaction just written. */
	public void advance(String newHash) {
		chainLength = chainLength + 1;
		headHash = newHash;
	}

	public long getChainLength() {
		return chainLength;
	}

	public String getHeadHash() {
		return headHash;
	}

	public boolean isIntegrityHold() {
		return integrityHold;
	}

}
