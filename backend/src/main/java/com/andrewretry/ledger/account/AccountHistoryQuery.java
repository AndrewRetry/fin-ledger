package com.andrewretry.ledger.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.andrewretry.ledger.account.dto.HistoryItem;
import com.andrewretry.ledger.account.dto.HistoryPage;
import com.andrewretry.ledger.common.Currencies;
import com.andrewretry.ledger.common.Money;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.journal.Direction;
import com.andrewretry.ledger.journal.TransactionType;

/**
 * An account's postings, oldest first. Ordered by account version, not by timestamp: postings of one account are
 * written under its row lock, so version order is exactly the order they happened in, with no ties.
 */
@Service
public class AccountHistoryQuery {

	private static final String PAGE_SQL = """
			select t.id, t.type, p.direction, p.amount, p.balance_after, p.account_version, t.created_at, t.description
			  from postings p
			  join journal_transactions t on t.id = p.transaction_id
			 where p.account_id = ?
			 order by p.account_version
			 limit ? offset ?""";

	private final AccountRepository accounts;
	private final JdbcTemplate jdbc;
	private final Currencies currencies;

	public AccountHistoryQuery(AccountRepository accounts, JdbcTemplate jdbc, Currencies currencies) {
		this.accounts = accounts;
		this.jdbc = jdbc;
		this.currencies = currencies;
	}

	@Transactional(readOnly = true)
	public HistoryPage history(UUID accountId, int page, int size) {
		AccountCurrencyView account = accounts.findViewById(accountId)
				.orElseThrow(() -> LedgerException.accountNotFound(accountId));
		String currency = account.currency();

		List<HistoryItem> items = jdbc.query(PAGE_SQL, (rs, row) -> new HistoryItem(
				rs.getObject("id", UUID.class),
				TransactionType.valueOf(rs.getString("type")),
				Direction.valueOf(rs.getString("direction")),
				Money.of(rs.getLong("amount"), currency, currencies),
				Money.of(rs.getLong("balance_after"), currency, currencies),
				rs.getLong("account_version"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getString("description")),
				accountId, size, (long) page * size);
		long total = jdbc.queryForObject("select count(*) from postings where account_id = ?", Long.class, accountId);
		int totalPages = (int) ((total + size - 1) / size);

		return new HistoryPage(accountId, currency, items, page, size, total, totalPages);
	}

}
