package com.andrewretry.ledger.journal;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One line of a journal transaction. {@code accountId} is a plain column on purpose: no relation to Account, so
 * reading postings can never load (and cache) an Account entity before it is locked.
 */
@Entity
@Immutable
@Table(name = "postings")
public class Posting {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "transaction_id", nullable = false)
	private JournalTransaction transaction;

	@Column(name = "account_id", nullable = false)
	private UUID accountId;

	@Column(nullable = false)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Direction direction;

	@Column(nullable = false)
	private long amount;

	@Column(name = "account_version", nullable = false)
	private long accountVersion;

	@Column(name = "balance_after", nullable = false)
	private long balanceAfter;

	protected Posting() {
	}

	Posting(JournalTransaction transaction, AppliedPosting applied) {
		this.id = UUID.randomUUID();
		this.transaction = transaction;
		this.accountId = applied.accountId();
		this.currency = applied.currency();
		this.direction = applied.direction();
		this.amount = applied.amount();
		this.accountVersion = applied.accountVersion();
		this.balanceAfter = applied.balanceAfter();
	}

	public AppliedPosting toApplied() {
		return new AppliedPosting(accountId, currency, direction, amount, accountVersion, balanceAfter);
	}

}
