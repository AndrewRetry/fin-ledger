package com.andrewretry.ledger.account;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A wallet. {@code balance} is a cache of its postings and {@code version} counts them. {@code version} is a plain
 * column, deliberately not {@code @Version}: writers are serialised by row locks, and the database keeps the
 * (account, version) pairs unique as a lost-update detector.
 */
@Entity
@Table(name = "accounts")
public class Account {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AccountKind kind;

	@Column(nullable = false)
	private String currency;

	@Column(nullable = false)
	private long balance;

	@Column(nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Account() {
	}

	/** A new, empty customer wallet. The database refuses accounts that do not start at balance 0, version 0. */
	public Account(String name, String currency, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.name = name;
		this.kind = AccountKind.CUSTOMER;
		this.currency = currency;
		this.balance = 0;
		this.version = 0;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public AccountKind getKind() {
		return kind;
	}

	public String getCurrency() {
		return currency;
	}

	public long getBalance() {
		return balance;
	}

	public long getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
