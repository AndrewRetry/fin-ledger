package com.andrewretry.ledger.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * How many units of {@code quote} one unit of {@code base} buys (SGD to USD 0.775: SGD 1.00 = USD 0.775).
 * Insert-only: a new rate is a new row, and the newest row for a pair is the one used.
 */
@Entity
@Immutable
@Table(name = "fx_rates")
public class FxRate {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String base;

	@Column(nullable = false)
	private String quote;

	@Column(nullable = false, precision = 24, scale = 12)
	private BigDecimal rate;

	@Column(nullable = false)
	private String source;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected FxRate() {
	}

	/** A rate entered by an administrator. */
	public static FxRate manual(String base, String quote, BigDecimal rate, Instant createdAt) {
		FxRate fx = new FxRate();
		fx.id = UUID.randomUUID();
		fx.base = base;
		fx.quote = quote;
		fx.rate = rate;
		fx.source = "MANUAL";
		fx.createdAt = createdAt;
		return fx;
	}

	public UUID getId() {
		return id;
	}

	public String getBase() {
		return base;
	}

	public String getQuote() {
		return quote;
	}

	public BigDecimal getRate() {
		return rate;
	}

	public String getSource() {
		return source;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
