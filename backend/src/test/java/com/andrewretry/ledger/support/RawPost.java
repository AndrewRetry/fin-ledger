package com.andrewretry.ledger.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes a journal transaction with plain SQL the way the service will, so a test can break one rule at a time.
 * Call it inside a transaction. Hashes are random, so only schema guardrails (not integrity checks) see them as valid.
 */
public final class RawPost {

	public record Leg(UUID account, String currency, String direction, long amount) {
		public static Leg debit(UUID account, String currency, long amount) {
			return new Leg(account, currency, "DEBIT", amount);
		}

		public static Leg credit(UUID account, String currency, long amount) {
			return new Leg(account, currency, "CREDIT", amount);
		}
	}

	/** Every field defaults to "behave like the service"; set one to break one rule. */
	public static final class Options {
		String key;
		String externalRef;
		UUID reversesId;
		UUID fxRateId;
		Long chainIndex;
		String prevHash;
		long[] versions;
		boolean advanceHead = true;
		boolean updateAccounts = true;

		public static Options defaults() {
			return new Options();
		}

		public Options key(String key) {
			this.key = key;
			return this;
		}

		public Options externalRef(String externalRef) {
			this.externalRef = externalRef;
			return this;
		}

		public Options reversesId(UUID reversesId) {
			this.reversesId = reversesId;
			return this;
		}

		public Options fxRateId(UUID fxRateId) {
			this.fxRateId = fxRateId;
			return this;
		}

		public Options chainIndex(long chainIndex) {
			this.chainIndex = chainIndex;
			return this;
		}

		public Options prevHash(String prevHash) {
			this.prevHash = prevHash;
			return this;
		}

		public Options versions(long... versions) {
			this.versions = versions;
			return this;
		}

		public Options advanceHead(boolean advanceHead) {
			this.advanceHead = advanceHead;
			return this;
		}

		public Options updateAccounts(boolean updateAccounts) {
			this.updateAccounts = updateAccounts;
			return this;
		}
	}

	private RawPost() {
	}

	public static UUID post(JdbcTemplate jdbc, String type, List<Leg> legs) {
		return post(jdbc, type, legs, Options.defaults());
	}

	/** Returns the id of the new transaction. */
	public static UUID post(JdbcTemplate jdbc, String type, List<Leg> legs, Options options) {
		Map<String, Object> head = jdbc.queryForMap("select chain_length, head_hash from ledger_state where id = 1 for update");
		long headLength = (Long) head.get("chain_length");
		String headHash = (String) head.get("head_hash");

		UUID transactionId = UUID.randomUUID();
		String txHash = randomHash();
		String key = options.key != null ? options.key : "raw-" + UUID.randomUUID();
		long chainIndex = options.chainIndex != null ? options.chainIndex : headLength + 1;
		String prevHash = options.prevHash != null ? options.prevHash : headHash;

		jdbc.update("""
				insert into journal_transactions
				  (id, chain_index, type, idempotency_key, request_hash, external_ref, reverses_id, fx_rate_id,
				   created_at, prev_hash, tx_hash)
				values (?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?, ?)""",
				transactionId, chainIndex, type, key, "0".repeat(64), options.externalRef, options.reversesId,
				options.fxRateId, prevHash, txHash);

		for (int i = 0; i < legs.size(); i++) {
			Leg leg = legs.get(i);
			Map<String, Object> account = jdbc.queryForMap("select balance, version from accounts where id = ? for update", leg.account());
			long balance = (Long) account.get("balance");
			long version = (Long) account.get("version");
			long newBalance = leg.direction().equals("CREDIT") ? balance + leg.amount() : balance - leg.amount();
			long newVersion = options.versions != null ? options.versions[i] : version + 1;

			jdbc.update("""
					insert into postings (id, transaction_id, account_id, currency, direction, amount, account_version, balance_after)
					values (?, ?, ?, ?, ?, ?, ?, ?)""",
					UUID.randomUUID(), transactionId, leg.account(), leg.currency(), leg.direction(), leg.amount(),
					newVersion, newBalance);

			if (options.updateAccounts) {
				jdbc.update("update accounts set balance = ?, version = ? where id = ?", newBalance, newVersion, leg.account());
			}
		}

		if (options.advanceHead) {
			jdbc.update("update ledger_state set chain_length = ?, head_hash = ? where id = 1", headLength + 1, txHash);
		}
		return transactionId;
	}

	private static String randomHash() {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
