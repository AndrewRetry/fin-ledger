package com.andrewretry.ledger.integrity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.andrewretry.ledger.journal.AppliedPosting;
import com.andrewretry.ledger.journal.Direction;
import com.andrewretry.ledger.journal.TransactionType;
import com.andrewretry.ledger.journal.TxHasher;

/**
 * Walks the whole hash chain from the first transaction and recomputes every link (PLAN.md §7.7). Reads straight
 * from the tables, in the caller's transaction. Editing any transaction changes its hash; deleting the newest one
 * leaves ledger_state pointing at a transaction that is gone.
 */
@Component
public class ChainVerifier {

	private static final int PAGE_SIZE = 500;
	private static final String ZEROS = "0".repeat(64);

	private final JdbcTemplate jdbc;

	public ChainVerifier(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** A stored transaction: everything that was hashed, plus the hash that was stored. */
	private record Stored(TxHasher.Input hashed, String storedHash) {
	}

	public List<Discrepancy> verify() {
		List<Discrepancy> found = new ArrayList<>();
		long expectedIndex = 1;
		String expectedPrev = ZEROS;
		List<Stored> page = readPage(0);
		while (!page.isEmpty()) {
			for (Stored tx : page) {
				TxHasher.Input hashed = tx.hashed();
				if (hashed.chainIndex() != expectedIndex || !hashed.prevHash().equals(expectedPrev)) {
					found.add(new Discrepancy(CheckCode.CHAIN_LINK_BROKEN, null, hashed.id(), null, expectedIndex,
							hashed.chainIndex(), "transaction does not link to the one before it (expected chain index "
									+ expectedIndex + " with prev_hash " + expectedPrev + ")"));
				}
				if (!TxHasher.hash(hashed).equals(tx.storedHash())) {
					found.add(new Discrepancy(CheckCode.CHAIN_TX_HASH_MISMATCH, null, hashed.id(), null, null, null,
							"stored tx_hash is not the hash of the transaction's contents: it was changed after writing"));
				}
				expectedIndex = hashed.chainIndex() + 1;
				expectedPrev = tx.storedHash();
			}
			page = readPage(page.getLast().hashed().chainIndex());
		}

		Map<String, Object> head = jdbc.queryForMap("select chain_length, head_hash from ledger_state where id = 1");
		long headLength = (Long) head.get("chain_length");
		if (headLength != expectedIndex - 1 || !head.get("head_hash").equals(expectedPrev)) {
			found.add(new Discrepancy(CheckCode.CHAIN_HEAD_MISMATCH, null, null, null, expectedIndex - 1, headLength,
					"ledger_state does not point at the newest transaction (newest chain index " + (expectedIndex - 1)
							+ ")"));
		}
		return found;
	}

	/** The next transactions after {@code afterIndex}, each with its postings. */
	private List<Stored> readPage(long afterIndex) {
		List<Stored> withoutPostings = jdbc.query(
				"select * from journal_transactions where chain_index > ? order by chain_index limit ?",
				(rs, row) -> new Stored(new TxHasher.Input(
						rs.getLong("chain_index"),
						rs.getString("prev_hash"),
						rs.getObject("id", UUID.class),
						TransactionType.valueOf(rs.getString("type")),
						rs.getObject("created_at", OffsetDateTime.class).toInstant(),
						rs.getString("idempotency_key"),
						rs.getString("external_ref"),
						rs.getObject("reverses_id", UUID.class),
						rs.getObject("fx_rate_id", UUID.class),
						rs.getString("description"),
						List.of()), rs.getString("tx_hash")),
				afterIndex, PAGE_SIZE);
		if (withoutPostings.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<AppliedPosting>> postings =
				postingsBetween(afterIndex, withoutPostings.getLast().hashed().chainIndex());
		return withoutPostings.stream().map(tx -> withPostings(tx, postings.getOrDefault(tx.hashed().id(), List.of())))
				.toList();
	}

	private static Stored withPostings(Stored tx, List<AppliedPosting> postings) {
		TxHasher.Input h = tx.hashed();
		return new Stored(new TxHasher.Input(h.chainIndex(), h.prevHash(), h.id(), h.type(), h.createdAt(),
				h.idempotencyKey(), h.externalRef(), h.reversesId(), h.fxRateId(), h.description(), postings),
				tx.storedHash());
	}

	private Map<UUID, List<AppliedPosting>> postingsBetween(long afterIndex, long lastIndex) {
		Map<UUID, List<AppliedPosting>> byTransaction = new HashMap<>();
		jdbc.query("""
				select p.transaction_id, p.account_id, p.currency, p.direction, p.amount, p.account_version, p.balance_after
				  from postings p
				  join journal_transactions t on t.id = p.transaction_id
				 where t.chain_index > ? and t.chain_index <= ?""", rs -> {
			byTransaction.computeIfAbsent(rs.getObject("transaction_id", UUID.class), k -> new ArrayList<>())
					.add(new AppliedPosting(rs.getObject("account_id", UUID.class), rs.getString("currency"),
							Direction.valueOf(rs.getString("direction")), rs.getLong("amount"),
							rs.getLong("account_version"), rs.getLong("balance_after")));
		}, afterIndex, lastIndex);
		return byTransaction;
	}

}
