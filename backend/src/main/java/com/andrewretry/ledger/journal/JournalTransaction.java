package com.andrewretry.ledger.journal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** A link of the hash chain: two or more postings that balance per currency. Written once, never changed. */
@Entity
@Immutable
@Table(name = "journal_transactions")
public class JournalTransaction {

	@Id
	private UUID id;

	@Column(name = "chain_index", nullable = false)
	private long chainIndex;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TransactionType type;

	@Column(name = "idempotency_key", nullable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", nullable = false)
	private String requestHash;

	@Column(name = "external_ref")
	private String externalRef;

	@Column(name = "reverses_id")
	private UUID reversesId;

	@Column(name = "fx_rate_id")
	private UUID fxRateId;

	private String description;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "prev_hash", nullable = false)
	private String prevHash;

	@Column(name = "tx_hash", nullable = false)
	private String txHash;

	@OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
	@OrderBy("accountId")
	private List<Posting> postings = new ArrayList<>();

	protected JournalTransaction() {
	}

	/** {@code hashed} carries every hashed field; the postings are stored in account-id order, like the hash. */
	public JournalTransaction(TxHasher.Input hashed, String requestHash, String txHash) {
		this.id = hashed.id();
		this.chainIndex = hashed.chainIndex();
		this.type = hashed.type();
		this.idempotencyKey = hashed.idempotencyKey();
		this.requestHash = requestHash;
		this.externalRef = hashed.externalRef();
		this.reversesId = hashed.reversesId();
		this.fxRateId = hashed.fxRateId();
		this.description = hashed.description();
		this.createdAt = hashed.createdAt();
		this.prevHash = hashed.prevHash();
		this.txHash = txHash;
		hashed.postings().stream().sorted(AppliedPosting.BY_ACCOUNT_ID)
				.forEach(applied -> postings.add(new Posting(this, applied)));
	}

	public UUID getId() {
		return id;
	}

	public long getChainIndex() {
		return chainIndex;
	}

	public TransactionType getType() {
		return type;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public String getExternalRef() {
		return externalRef;
	}

	public UUID getReversesId() {
		return reversesId;
	}

	public UUID getFxRateId() {
		return fxRateId;
	}

	public String getDescription() {
		return description;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getPrevHash() {
		return prevHash;
	}

	public String getTxHash() {
		return txHash;
	}

	public List<AppliedPosting> getPostings() {
		return postings.stream().map(Posting::toApplied).toList();
	}

}
