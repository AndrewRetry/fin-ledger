package com.andrewretry.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import com.andrewretry.ledger.support.Api;
import com.andrewretry.ledger.support.Api.Response;
import com.andrewretry.ledger.support.Fixtures;
import com.andrewretry.ledger.support.IntegrationTest;
import com.andrewretry.ledger.support.TxHashes;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class ReversalApiIT {

	private final Api api;
	private final Fixtures fixtures;

	ReversalApiIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
		this.fixtures = new Fixtures(api);
	}

	@Test
	void reversesATransferByMirroringItsPostings() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		JsonNode original = transfer(alice, bob, 2_500).json();

		Response reversal = reverse(original.path("id").asString(), "customer dispute");

		assertThat(reversal.status()).isEqualTo(201);
		JsonNode tx = reversal.json();
		assertThat(tx.path("type").asString()).isEqualTo("REVERSAL");
		assertThat(tx.path("reversesId").asString()).isEqualTo(original.path("id").asString());
		assertThat(tx.path("description").asString()).isEqualTo("customer dispute");
		assertThat(direction(tx, alice)).isEqualTo("CREDIT");
		assertThat(direction(tx, bob)).isEqualTo("DEBIT");
		assertThat(fixtures.balance(alice)).isEqualTo(10_000);
		assertThat(fixtures.balance(bob)).isZero();
		assertThat(TxHasher.hash(TxHashes.inputOf(tx))).isEqualTo(tx.path("txHash").asString());
	}

	@Test
	void linksTheOriginalToItsReversal() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		String original = transfer(alice, bob, 100).text("id");
		String reversal = reverse(original, "entered twice").text("id");

		assertThat(api.get("/transactions/" + original).text("reversedById")).isEqualTo(reversal);
	}

	@Test
	void reversesADeposit() {
		UUID alice = fixtures.customer("Alice", "SGD");
		String deposit = fixtures.deposit(alice, 5_000).text("id");

		assertThat(reverse(deposit, "bank recalled it").status()).isEqualTo(201);
		assertThat(fixtures.balance(alice)).isZero();
	}

	@Test
	void refusesASecondReversal() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		String original = transfer(alice, bob, 100).text("id");
		reverse(original, "first");

		assertThat(code(reverse(original, "again"))).isEqualTo("409 ALREADY_REVERSED");
		assertThat(fixtures.balance(alice)).isEqualTo(10_000);
	}

	@Test
	void refusesToReverseAReversal() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		String reversal = reverse(transfer(alice, bob, 100).text("id"), "first").text("id");

		assertThat(code(reverse(reversal, "undo the undo"))).isEqualTo("422 CANNOT_REVERSE_REVERSAL");
	}

	@Test
	void refusesWhenTheRecipientAlreadySpentTheMoney() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		UUID carol = fixtures.customer("Carol", "SGD");
		String original = transfer(alice, bob, 2_500).text("id");
		transfer(bob, carol, 2_000);

		assertThat(code(reverse(original, "too late"))).isEqualTo("422 INSUFFICIENT_FUNDS");
		assertThat(fixtures.balance(bob)).isEqualTo(500);
	}

	@Test
	void replaysARetriedReversalInsteadOfReportingItAsAlreadyReversed() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		String original = transfer(alice, bob, 100).text("id");
		String key = "reversal-" + UUID.randomUUID();

		Response first = reverse(original, "dispute", key);
		Response retry = reverse(original, "dispute", key);

		assertThat(retry.status()).isEqualTo(201);
		assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");
		assertThat(retry.body()).isEqualTo(first.body());
	}

	/** An FX transfer is reversed at its original rate: both currencies come back exactly, house wallets too. */
	@Test
	void reversesAnFxTransferExactlyInBothCurrencies() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID carol = fixtures.customer("Carol", "USD");
		UUID houseSgd = fixtures.systemAccount("FX_LIQUIDITY", "SGD");
		UUID houseUsd = fixtures.systemAccount("FX_LIQUIDITY", "USD");
		long houseSgdBefore = fixtures.balance(houseSgd);
		long houseUsdBefore = fixtures.balance(houseUsd);
		String original = transfer(alice, carol, 10_000).text("id");

		Response reversal = reverse(original, "wrong recipient");

		assertThat(reversal.status()).isEqualTo(201);
		assertThat(reversal.json().path("postings")).hasSize(4);
		assertThat(reversal.json().path("fx").isNull()).isTrue();
		assertThat(fixtures.balance(alice)).isEqualTo(10_000);
		assertThat(fixtures.balance(carol)).isZero();
		assertThat(fixtures.balance(houseSgd)).isEqualTo(houseSgdBefore);
		assertThat(fixtures.balance(houseUsd)).isEqualTo(houseUsdBefore);
	}

	@Test
	void answers404ForAnUnknownTransaction() {
		assertThat(code(reverse(UUID.randomUUID().toString(), "nothing there"))).isEqualTo("404 TRANSACTION_NOT_FOUND");
	}

	@Test
	void requiresAReason() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		String original = transfer(alice, bob, 100).text("id");

		assertThat(code(api.post("/transactions/" + original + "/reversals", "{\"reason\": \"  \"}")))
				.isEqualTo("400 VALIDATION_FAILED");
		assertThat(code(api.post("/transactions/" + original + "/reversals", "{\"reason\": \"" + "x".repeat(281) + "\"}")))
				.isEqualTo("400 VALIDATION_FAILED");
	}

	private Response transfer(UUID source, UUID destination, long amount) {
		return api.post("/transactions", "{\"sourceAccountId\": \"" + source + "\", \"destinationAccountId\": \""
				+ destination + "\", \"amount\": " + amount + "}");
	}

	private Response reverse(String transactionId, String reason, String... headers) {
		return api.post("/transactions/" + transactionId + "/reversals", "{\"reason\": \"" + reason + "\"}", headers.length == 0
				? new String[0] : new String[] { Api.IDEMPOTENCY_KEY, headers[0] });
	}

	private static String direction(JsonNode tx, UUID accountId) {
		for (JsonNode p : tx.path("postings")) {
			if (p.path("accountId").asString().equals(accountId.toString())) {
				return p.path("direction").asString();
			}
		}
		throw new AssertionError("no posting for " + accountId);
	}

	private static String code(Response response) {
		return response.status() + " " + response.text("code");
	}

}
