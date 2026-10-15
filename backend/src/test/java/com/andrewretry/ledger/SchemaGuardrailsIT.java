package com.andrewretry.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.andrewretry.ledger.support.IntegrationTest;
import com.andrewretry.ledger.support.RawPost;
import com.andrewretry.ledger.support.RawPost.Leg;
import com.andrewretry.ledger.support.RawPost.Options;

/**
 * Appendix B of PLAN.md: every database guardrail, attacked directly with SQL.
 * Own Spring context and container (the unique property below), because the fixtures carry fake hashes
 * that would make integrity checks in other tests fail.
 */
@IntegrationTest
@TestPropertySource(properties = "guardrails.context=schema-guardrails")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaGuardrailsIT {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	UUID a;
	UUID b;
	UUID c;
	UUID fundingSgd;
	UUID transfer;
	UUID reversal;
	UUID unreversedTransfer;

	@BeforeAll
	void createFixtures() {
		a = UUID.randomUUID();
		b = UUID.randomUUID();
		c = UUID.randomUUID();
		fundingSgd = jdbc.queryForObject("select id from accounts where kind = 'FUNDING' and currency = 'SGD'", UUID.class);

		commit(() -> {
			insertCustomer(a, "Guard A", "SGD");
			insertCustomer(b, "Guard B", "SGD");
			insertCustomer(c, "Guard C", "USD");
		});
		commit(() -> RawPost.post(jdbc, "DEPOSIT",
				List.of(Leg.debit(fundingSgd, "SGD", 100_000), Leg.credit(a, "SGD", 100_000)),
				Options.defaults().externalRef("guard-ext-1")));
		transfer = commitReturning(() -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "SGD", 2_500), Leg.credit(b, "SGD", 2_500)),
				Options.defaults().key("guard-t1")));
		reversal = commitReturning(() -> RawPost.post(jdbc, "REVERSAL",
				List.of(Leg.debit(b, "SGD", 2_500), Leg.credit(a, "SGD", 2_500)),
				Options.defaults().reversesId(transfer)));
		unreversedTransfer = commitReturning(() -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "SGD", 1_000), Leg.credit(b, "SGD", 1_000))));
	}

	// ---- 1-4: append-only tables -------------------------------------------------------------

	@Test
	void case01_postingsCannotBeUpdated() {
		rejects("append-only", () -> jdbc.update("update postings set amount = amount + 1"));
	}

	@Test
	void case02_journalTransactionsCannotBeDeleted() {
		rejects("append-only", () -> jdbc.update("delete from journal_transactions"));
	}

	@Test
	void case03_postingsCannotBeTruncated() {
		rejects("append-only", () -> jdbc.execute("truncate postings cascade"));
	}

	@Test
	void case04_accountsCannotBeDeleted() {
		rejects("append-only", () -> jdbc.update("delete from accounts where id = ?", b));
	}

	// ---- 5-9: account rules ------------------------------------------------------------------

	@Test
	void case05_accountCurrencyIsImmutable() {
		rejects("immutable", () -> jdbc.update("update accounts set currency = 'USD' where id = ?", b));
	}

	@Test
	void case06_accountVersionCannotGoDown() {
		rejects("cannot go down", () -> jdbc.update("update accounts set version = version - 1 where id = ?", a));
	}

	@Test
	void case07_balanceCannotChangeWithoutNewVersion() {
		rejects("without a new version", () -> jdbc.update("update accounts set balance = balance + 100 where id = ?", a));
	}

	@Test
	void case08_balanceCannotChangeWithoutMatchingPosting() {
		rejects("without a matching posting",
				() -> jdbc.update("update accounts set balance = balance + 100, version = version + 1 where id = ?", a));
	}

	@Test
	void case09_newAccountMustStartEmpty() {
		rejects("must start empty", () -> jdbc.update(
				"insert into accounts (id, name, kind, currency, balance) values (?, 'Rich', 'CUSTOMER', 'SGD', 100)",
				UUID.randomUUID()));
	}

	// ---- 10-14: balance and shape of a transaction -------------------------------------------

	@Test
	void case10_unbalancedTransactionIsRejectedAtCommit() {
		rejects("unbalanced", () -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "SGD", 100), Leg.credit(b, "SGD", 99))));
	}

	@Test
	void case11_singleLegDepositIsRejectedAtCommit() {
		rejects("posting(s)", () -> RawPost.post(jdbc, "DEPOSIT", List.of(Leg.credit(b, "SGD", 100))));
	}

	@Test
	void case12_postingCurrencyMustMatchAccountCurrency() {
		rejects("fk_postings_account_currency", () -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "USD", 100), Leg.credit(c, "USD", 100))));
	}

	@Test
	void case13_customerBalanceCannotGoNegative() {
		rejects("ck_accounts_non_negative", () -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(b, "SGD", 1_000_000_000_000_000L), Leg.credit(a, "SGD", 1_000_000_000_000_000L))));
	}

	@Test
	void case14_fundingBalanceCannotBecomePositive() {
		rejects("ck_accounts_funding_non_positive", () -> jdbc.update(
				"update accounts set balance = 500, version = version + 1 where id = ?", fundingSgd));
	}

	// ---- 15-21: keys, reversals, versions ----------------------------------------------------

	@Test
	void case15_idempotencyKeyCannotBeUsedTwice() {
		rejects("uq_jt_idempotency_key", () -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "SGD", 1), Leg.credit(b, "SGD", 1)), Options.defaults().key("guard-t1")));
	}

	@Test
	void case16_transactionCanBeReversedOnlyOnce() {
		rejects("uq_jt_reverses_id", () -> RawPost.post(jdbc, "REVERSAL",
				List.of(Leg.debit(b, "SGD", 2_500), Leg.credit(a, "SGD", 2_500)), Options.defaults().reversesId(transfer)));
	}

	@Test
	void case17_reversalCannotBeReversed() {
		rejects("cannot be reversed", () -> RawPost.post(jdbc, "REVERSAL",
				List.of(Leg.debit(a, "SGD", 2_500), Leg.credit(b, "SGD", 2_500)), Options.defaults().reversesId(reversal)));
	}

	@Test
	void case18_reversalMustMirrorTheOriginal() {
		rejects("does not mirror", () -> RawPost.post(jdbc, "REVERSAL",
				List.of(Leg.debit(b, "SGD", 999), Leg.credit(a, "SGD", 999)), Options.defaults().reversesId(unreversedTransfer)));
	}

	@Test
	void case19_transactionWithNoPostingsIsRejectedAtCommit() {
		rejects("posting(s)", () -> RawPost.post(jdbc, "TRANSFER", List.of()));
	}

	@Test
	void case20_accountVersionCannotBeClaimedTwice() {
		rejects("uq_postings_account_version", () -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "SGD", 1), Leg.credit(b, "SGD", 1)), Options.defaults().versions(1, 1)));
	}

	@Test
	void case21_fxTransferNeedsAnFxRate() {
		rejects("ck_jt_fx_link", () -> RawPost.post(jdbc, "FX_TRANSFER",
				List.of(Leg.debit(a, "SGD", 1), Leg.credit(b, "SGD", 1))));
	}

	// ---- 22-29: hash chain and ledger_state --------------------------------------------------

	@Test
	void case22_chainIndexMustBeTheNextOne() {
		rejects("not the next index", () -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "SGD", 1), Leg.credit(b, "SGD", 1)), Options.defaults().chainIndex(1_000_000)));
	}

	@Test
	void case23_prevHashMustLinkToTheNewestTransaction() {
		rejects("does not link", () -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "SGD", 1), Leg.credit(b, "SGD", 1)), Options.defaults().prevHash("f".repeat(64))));
	}

	@Test
	void case24_transactionMustAdvanceTheChainHead() {
		rejects("without advancing the chain head", () -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "SGD", 1), Leg.credit(b, "SGD", 1)), Options.defaults().advanceHead(false)));
	}

	@Test
	void case25_chainHeadMustMatchTheNewestTransaction() {
		rejects("does not match the newest", () -> jdbc.update(
				"update ledger_state set chain_length = chain_length + 1, head_hash = ?", "a".repeat(64)));
	}

	@Test
	void case26_chainHeadAdvancesOneTransactionAtATime() {
		rejects("one transaction at a time", () -> jdbc.update("update ledger_state set chain_length = chain_length + 2"));
	}

	@Test
	void case27_balanceChangeMustBeWrittenToTheAccount() {
		rejects("still at version", () -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "SGD", 1), Leg.credit(b, "SGD", 1)), Options.defaults().updateAccounts(false)));
	}

	@Test
	void case28_ledgerStateCannotBeDeleted() {
		rejects("append-only", () -> jdbc.update("delete from ledger_state"));
	}

	@Test
	void case29_integrityHoldNeedsAReason() {
		rejects("ck_ledger_state_hold", () -> jdbc.update("update ledger_state set integrity_hold = true"));
	}

	// ---- 30-33: fx rates, snapshots, currencies ----------------------------------------------

	@Test
	void case30_fxRatesCannotBeUpdated() {
		rejects("append-only", () -> jdbc.update("update fx_rates set rate = rate * 2"));
	}

	@Test
	void case31_snapshotNeedsACleanFinalMonthEndRun() {
		rejects("clean, final month-end run", () -> {
			UUID run = insertMonthEndRun("DISCREPANCIES", 1);
			insertSnapshot(run, 0, 0, 5, 5);
		});
	}

	@Test
	void case32_snapshotArithmeticMustAdd() {
		rejects("ck_apb_arithmetic", () -> {
			UUID run = insertMonthEndRun("CLEAN", 0);
			insertSnapshot(run, 0, 0, 5, 7);
		});
	}

	@Test
	void case33_currenciesCannotBeUpdated() {
		rejects("append-only", () -> jdbc.update("update currencies set minor_units = 3"));
	}

	// ---- 34-35: external references ----------------------------------------------------------

	@Test
	void case34_externalRefCannotBeUsedTwice() {
		rejects("uq_jt_external_ref", () -> RawPost.post(jdbc, "DEPOSIT",
				List.of(Leg.debit(fundingSgd, "SGD", 1), Leg.credit(b, "SGD", 1)),
				Options.defaults().externalRef("guard-ext-1")));
	}

	@Test
	void case35_externalRefOnlyOnDepositsAndWithdrawals() {
		rejects("ck_jt_external_ref_kind", () -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "SGD", 1), Leg.credit(b, "SGD", 1)), Options.defaults().externalRef("guard-ext-2")));
	}

	// ---- the rejected attempts left nothing behind -------------------------------------------

	@Test
	void aValidTransferStillCommitsAfterTheRejectedAttempts() {
		UUID id = commitReturning(() -> RawPost.post(jdbc, "TRANSFER",
				List.of(Leg.debit(a, "SGD", 1), Leg.credit(b, "SGD", 1))));

		Integer postings = jdbc.queryForObject("select count(*) from postings where transaction_id = ?", Integer.class, id);
		assertThat(postings).isEqualTo(2);
	}

	// ---- helpers -----------------------------------------------------------------------------

	private void insertCustomer(UUID id, String name, String currency) {
		jdbc.update("insert into accounts (id, name, kind, currency) values (?, ?, 'CUSTOMER', ?)", id, name, currency);
	}

	private UUID insertMonthEndRun(String status, int discrepancies) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into reconciliation_runs
				  (id, kind, period, provisional, status, started_at, finished_at,
				   checked_accounts, checked_transactions, discrepancy_count)
				values (?, 'MONTH_END', date '2020-01-01', false, ?, now(), now(), 0, 0, ?)""",
				id, status, discrepancies);
		return id;
	}

	private void insertSnapshot(UUID run, long opening, long debits, long credits, long closing) {
		jdbc.update("""
				insert into account_period_balances
				  (account_id, period, currency, opening_balance, period_debits, period_credits,
				   closing_balance, last_account_version, run_id)
				values (?, date '2020-01-01', 'SGD', ?, ?, ?, ?, 0, ?)""",
				a, opening, debits, credits, closing, run);
	}

	private void commit(Runnable body) {
		tx.executeWithoutResult(status -> body.run());
	}

	private UUID commitReturning(java.util.function.Supplier<UUID> body) {
		return tx.execute(status -> body.get());
	}

	/** Runs the body in its own transaction and expects it to fail with the fragment somewhere in the cause chain. */
	private void rejects(String fragment, Runnable body) {
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> body.run()))
				.satisfies(e -> assertThat(messagesOf(e)).contains(fragment));
	}

	private static String messagesOf(Throwable e) {
		StringBuilder all = new StringBuilder();
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			all.append(t.getMessage()).append('\n');
		}
		return all.toString();
	}

}
