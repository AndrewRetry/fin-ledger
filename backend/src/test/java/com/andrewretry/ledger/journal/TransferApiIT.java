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
class TransferApiIT {

	private final Api api;
	private final Fixtures fixtures;

	TransferApiIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
		this.fixtures = new Fixtures(api);
	}

	@Test
	void movesMoneyFromSourceToDestination() {
		UUID alice = fixtures.funded("Alice", "SGD", 100_000);
		UUID bob = fixtures.customer("Bob", "SGD");

		Response response = transfer(alice, bob, 2500, "\"description\": \"Lunch split\"");

		assertThat(response.status()).isEqualTo(201);
		JsonNode tx = response.json();
		assertThat(response.header("Location")).isEqualTo("/transactions/" + tx.path("id").asString());
		assertThat(tx.path("type").asString()).isEqualTo("TRANSFER");
		assertThat(tx.path("status").asString()).isEqualTo("POSTED");
		assertThat(tx.path("description").asString()).isEqualTo("Lunch split");
		assertThat(tx.path("chainIndex").asLong()).isPositive();
		assertThat(tx.path("txHash").asString()).matches("[0-9a-f]{64}");
		assertThat(tx.path("postings")).hasSize(2);
		assertThat(fixtures.balance(alice)).isEqualTo(97_500);
		assertThat(fixtures.balance(bob)).isEqualTo(2_500);
	}

	@Test
	void recordsBothPostingsWithTheirRunningBalances() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");

		JsonNode tx = transfer(alice, bob, 2500, null).json();

		JsonNode debit = posting(tx, alice);
		assertThat(debit.path("direction").asString()).isEqualTo("DEBIT");
		assertThat(debit.path("amount").path("display").asString()).isEqualTo("25.00");
		assertThat(debit.path("balanceAfter").path("amount").asLong()).isEqualTo(7_500);
		assertThat(debit.path("accountVersion").asLong()).isEqualTo(2);
		JsonNode credit = posting(tx, bob);
		assertThat(credit.path("direction").asString()).isEqualTo("CREDIT");
		assertThat(credit.path("balanceAfter").path("amount").asLong()).isEqualTo(2_500);
		assertThat(credit.path("accountVersion").asLong()).isEqualTo(1);
	}

	/** An auditor can check a transaction from the API alone: the response carries every hashed field. */
	@Test
	void linksConsecutiveTransactionsIntoAVerifiableHashChain() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");

		JsonNode first = transfer(alice, bob, 100, "\"description\": \"one\"").json();
		JsonNode second = transfer(alice, bob, 200, null).json();

		assertThat(second.path("chainIndex").asLong()).isEqualTo(first.path("chainIndex").asLong() + 1);
		assertThat(second.path("prevHash").asString()).isEqualTo(first.path("txHash").asString());
		assertThat(TxHasher.hash(TxHashes.inputOf(first))).isEqualTo(first.path("txHash").asString());
		assertThat(TxHasher.hash(TxHashes.inputOf(second))).isEqualTo(second.path("txHash").asString());
		assertThat(api.get("/ledger/head").json().path("headHash").asString()).isEqualTo(second.path("txHash").asString());
	}

	@Test
	void returnsAStoredTransactionById() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		JsonNode posted = transfer(alice, bob, 100, null).json();

		Response fetched = api.get("/transactions/" + posted.path("id").asString());

		assertThat(fetched.status()).isEqualTo(200);
		assertThat(fetched.json().path("txHash").asString()).isEqualTo(posted.path("txHash").asString());
		assertThat(fetched.json().has("reversedById")).isTrue();
		assertThat(fetched.json().path("reversedById").isNull()).isTrue();
		assertThat(posted.has("reversedById")).isFalse();
	}

	@Test
	void answers404ForAnUnknownTransaction() {
		Response response = api.get("/transactions/" + UUID.randomUUID());

		assertThat(response.status()).isEqualTo(404);
		assertThat(response.text("code")).isEqualTo("TRANSACTION_NOT_FOUND");
	}

	@Test
	void acceptsTheSourceCurrencyWhenSent() {
		UUID alice = fixtures.funded("Alice", "SGD", 1_000);
		UUID bob = fixtures.customer("Bob", "SGD");

		assertThat(transfer(alice, bob, 100, "\"currency\": \"SGD\"").status()).isEqualTo(201);
	}

	// ---- rejections: nothing is written ------------------------------------------------------

	@Test
	void rejectsTransferWhenFundsAreInsufficient() {
		UUID alice = fixtures.funded("Alice", "SGD", 1_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		long chainBefore = chainLength();

		Response response = transfer(alice, bob, 1_001, null);

		assertThat(response.status()).isEqualTo(422);
		assertThat(response.text("code")).isEqualTo("INSUFFICIENT_FUNDS");
		assertThat(fixtures.balance(alice)).isEqualTo(1_000);
		assertThat(fixtures.balance(bob)).isZero();
		assertThat(chainLength()).isEqualTo(chainBefore);
	}

	@Test
	void rejectsTransferToTheSameAccount() {
		UUID alice = fixtures.funded("Alice", "SGD", 1_000);

		assertThat(code(transfer(alice, alice, 100, null))).isEqualTo("422 SAME_ACCOUNT");
	}

	@Test
	void rejectsAnUnknownAccount() {
		UUID alice = fixtures.funded("Alice", "SGD", 1_000);

		assertThat(code(transfer(alice, UUID.randomUUID(), 100, null))).isEqualTo("404 ACCOUNT_NOT_FOUND");
		assertThat(code(transfer(UUID.randomUUID(), alice, 100, null))).isEqualTo("404 ACCOUNT_NOT_FOUND");
	}

	@Test
	void rejectsACurrencyThatIsNotTheSourceCurrency() {
		UUID alice = fixtures.funded("Alice", "SGD", 1_000);
		UUID bob = fixtures.customer("Bob", "SGD");

		assertThat(code(transfer(alice, bob, 100, "\"currency\": \"USD\""))).isEqualTo("422 CURRENCY_MISMATCH");
	}

	@Test
	void rejectsATransferToASystemAccount() {
		UUID alice = fixtures.funded("Alice", "SGD", 1_000);
		UUID funding = fixtures.systemAccount("FUNDING", "SGD");

		assertThat(code(transfer(alice, funding, 100, null))).isEqualTo("422 ACCOUNT_KIND_NOT_ALLOWED");
	}

	@Test
	void rejectsAFractionalAmount() {
		assertThat(code(rawTransfer("10.5"))).isEqualTo("400 VALIDATION_FAILED");
	}

	@Test
	void rejectsAnAmountSentAsAString() {
		assertThat(code(rawTransfer("\"100\""))).isEqualTo("400 VALIDATION_FAILED");
	}

	@Test
	void rejectsAZeroAmount() {
		assertThat(code(rawTransfer("0"))).isEqualTo("400 VALIDATION_FAILED");
	}

	@Test
	void rejectsAnUnknownField() {
		UUID id = UUID.randomUUID();
		Response response = api.post("/transactions", "{\"sourceAccountId\": \"" + id + "\", \"destinationAccountId\": \""
				+ id + "\", \"amount\": 1, \"memo\": \"x\"}");

		assertThat(code(response)).isEqualTo("400 VALIDATION_FAILED");
	}

	@Test
	void rejectsARequestWithoutIdempotencyKey() {
		UUID alice = fixtures.funded("Alice", "SGD", 1_000);
		UUID bob = fixtures.customer("Bob", "SGD");

		Response response = api.postWithoutKey("/transactions", body(alice, bob, 100, null));

		assertThat(code(response)).isEqualTo("400 IDEMPOTENCY_KEY_MISSING");
		assertThat(fixtures.balance(alice)).isEqualTo(1_000);
	}

	@Test
	void rejectsAMalformedIdempotencyKey() {
		UUID alice = fixtures.funded("Alice", "SGD", 1_000);
		UUID bob = fixtures.customer("Bob", "SGD");

		Response response = api.post("/transactions", body(alice, bob, 100, null), Api.IDEMPOTENCY_KEY, "has space");

		assertThat(code(response)).isEqualTo("400 VALIDATION_FAILED");
	}

	// ---- helpers -----------------------------------------------------------------------------

	private Response transfer(UUID source, UUID destination, long amount, String extraFields) {
		return api.post("/transactions", body(source, destination, amount, extraFields));
	}

	private static String body(UUID source, UUID destination, long amount, String extraFields) {
		return "{\"sourceAccountId\": \"" + source + "\", \"destinationAccountId\": \"" + destination
				+ "\", \"amount\": " + amount + (extraFields == null ? "" : ", " + extraFields) + "}";
	}

	private Response rawTransfer(String amountJson) {
		UUID id = UUID.randomUUID();
		return api.post("/transactions", "{\"sourceAccountId\": \"" + id + "\", \"destinationAccountId\": \""
				+ UUID.randomUUID() + "\", \"amount\": " + amountJson + "}");
	}

	private long chainLength() {
		return api.get("/ledger/head").json().path("chainLength").asLong();
	}

	private static String code(Response response) {
		return response.status() + " " + response.text("code");
	}

	private static JsonNode posting(JsonNode tx, UUID accountId) {
		for (JsonNode p : tx.path("postings")) {
			if (p.path("accountId").asString().equals(accountId.toString())) {
				return p;
			}
		}
		throw new AssertionError("no posting for " + accountId);
	}

}
