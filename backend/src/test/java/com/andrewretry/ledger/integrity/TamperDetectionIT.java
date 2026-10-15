package com.andrewretry.ledger.integrity;

import static com.andrewretry.ledger.support.FixedClockConfig.thisMonth;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.andrewretry.ledger.support.Api;
import com.andrewretry.ledger.support.Api.Response;
import com.andrewretry.ledger.support.FixedClockConfig;
import com.andrewretry.ledger.support.Fixtures;
import com.andrewretry.ledger.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * Someone with full database access edits history behind the application's back, and the checks catch it.
 * Edits run as superuser with {@code session_replication_role = replica}, which switches the append-only triggers
 * off for that transaction. Own context and database (the property below), because it breaks data on purpose;
 * every test puts the data back and releases the hold in {@code finally}.
 */
@IntegrationTest
@Import(FixedClockConfig.class)
@TestPropertySource(properties = "test.context=tamper-detection")
class TamperDetectionIT {

	private final Api api;
	private final Fixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	TamperDetectionIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
		this.fixtures = new Fixtures(api);
	}

	@Test
	void raisedPostingAmountIsCaughtAndStopsWritesButNotReads() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		String key = "tamper-" + UUID.randomUUID();
		String body = transferBody(alice, bob, 2_500);
		String transfer = api.post("/transactions", body, Api.IDEMPOTENCY_KEY, key).text("id");
		assertThat(runChecks().path("status").asString()).isEqualTo("CLEAN");

		tamper("update postings set amount = amount + 1 where transaction_id = ?::uuid and account_id = ?::uuid",
				transfer, bob.toString());
		try {
			JsonNode report = runChecks();

			assertThat(report.path("status").asString()).isEqualTo("DISCREPANCIES");
			assertThat(codes(report)).contains("TRIAL_BALANCE", "TRANSACTION_BALANCE", "ACCOUNT_BALANCE",
					"CHAIN_TX_HASH_MISMATCH");
			assertThat(report.path("integrityHold").asBoolean()).isTrue();

			Response write = api.post("/transactions", transferBody(alice, bob, 100));
			assertThat(write.status() + " " + write.text("code")).isEqualTo("503 LEDGER_INTEGRITY_HOLD");

			Response read = api.get("/accounts/" + alice);
			assertThat(read.status()).isEqualTo(200);
			assertThat(read.header("X-Ledger-Integrity")).isEqualTo("HOLD");

			Response replay = api.post("/transactions", body, Api.IDEMPOTENCY_KEY, key);  // step 3 comes before the hold
			assertThat(replay.status()).isEqualTo(201);
			assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
		}
		finally {
			tamper("update postings set amount = amount - 1 where transaction_id = ?::uuid and account_id = ?::uuid",
					transfer, bob.toString());
			releaseHold();
		}
		assertThat(runChecks().path("status").asString()).isEqualTo("CLEAN");
	}

	/** Balances still add up, so only the hash chain can notice. */
	@Test
	void editedDescriptionIsCaughtOnlyByTheHashChain() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		String transfer = api.post("/transactions", "{\"sourceAccountId\": \"" + alice + "\", \"destinationAccountId\": \""
				+ bob + "\", \"amount\": 100, \"description\": \"rent\"}").text("id");

		tamper("update journal_transactions set description = 'refund' where id = ?::uuid", transfer);
		try {
			JsonNode report = runChecks();

			assertThat(codes(report)).containsExactly("CHAIN_TX_HASH_MISMATCH");
			assertThat(report.path("discrepancies").get(0).path("transactionId").asString()).isEqualTo(transfer);
		}
		finally {
			tamper("update journal_transactions set description = 'rent' where id = ?::uuid", transfer);
			releaseHold();
		}
		assertThat(runChecks().path("status").asString()).isEqualTo("CLEAN");
	}

	/** The month was closed clean and frozen; editing it afterwards shows up when it is closed again. */
	@Test
	void editInAClosedMonthIsCaughtByMonthEnd() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		String transfer = api.post("/transactions", transferBody(alice, bob, 2_500)).text("id");
		assertThat(closeThisMonth().path("status").asString()).isEqualTo("CLEAN");

		tamper("update postings set amount = amount + 1 where transaction_id = ?::uuid and account_id = ?::uuid",
				transfer, bob.toString());
		try {
			JsonNode report = closeThisMonth();

			assertThat(report.path("status").asString()).isEqualTo("DISCREPANCIES");
			assertThat(codes(report)).contains("MONTH_END_CLOSING_MISMATCH");
			assertThat(report.path("integrityHold").asBoolean()).isTrue();
		}
		finally {
			tamper("update postings set amount = amount - 1 where transaction_id = ?::uuid and account_id = ?::uuid",
					transfer, bob.toString());
			releaseHold();
		}
	}

	/** One transaction with the append-only triggers switched off, as only a database superuser could. */
	private void tamper(String sql, Object... args) {
		tx.executeWithoutResult(status -> {
			jdbc.execute("set local session_replication_role = replica");
			jdbc.update(sql, args);
		});
	}

	private JsonNode runChecks() {
		return api.post("/integrity/checks", "").json();
	}

	private JsonNode closeThisMonth() {
		return api.post("/reconciliations/month-end?period=" + thisMonth(), "").json();
	}

	private void releaseHold() {
		Response released = api.admin("/admin/integrity-hold/release", "{\"reason\": \"tampering reverted by test\"}");
		assertThat(released.json().path("held").asBoolean()).isFalse();
	}

	private static List<String> codes(JsonNode report) {
		List<String> codes = new ArrayList<>();
		report.path("discrepancies").forEach(d -> {
			if (!codes.contains(d.path("checkCode").asString())) {
				codes.add(d.path("checkCode").asString());
			}
		});
		return codes;
	}

	private static String transferBody(UUID source, UUID destination, long amount) {
		return "{\"sourceAccountId\": \"" + source + "\", \"destinationAccountId\": \"" + destination
				+ "\", \"amount\": " + amount + "}";
	}

}
