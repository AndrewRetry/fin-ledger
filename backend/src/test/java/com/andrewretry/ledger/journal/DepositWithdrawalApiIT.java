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
class DepositWithdrawalApiIT {

	private final Api api;
	private final Fixtures fixtures;

	DepositWithdrawalApiIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
		this.fixtures = new Fixtures(api);
	}

	@Test
	void depositMovesMoneyInFromTheOutsideWorld() {
		UUID alice = fixtures.customer("Alice", "SGD");
		UUID funding = fixtures.systemAccount("FUNDING", "SGD");
		long fundingBefore = fixtures.balance(funding);

		Response response = movement("/deposits", alice, 10_000, null);

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.text("type")).isEqualTo("DEPOSIT");
		assertThat(fixtures.balance(alice)).isEqualTo(10_000);
		assertThat(fixtures.balance(funding)).isEqualTo(fundingBefore - 10_000);
	}

	@Test
	void withdrawalMovesMoneyBackOut() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID funding = fixtures.systemAccount("FUNDING", "SGD");
		long fundingBefore = fixtures.balance(funding);

		Response response = movement("/withdrawals", alice, 4_000, null);

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.text("type")).isEqualTo("WITHDRAWAL");
		assertThat(fixtures.balance(alice)).isEqualTo(6_000);
		assertThat(fixtures.balance(funding)).isEqualTo(fundingBefore + 4_000);
	}

	@Test
	void rejectsWithdrawingMoreThanTheBalance() {
		UUID alice = fixtures.funded("Alice", "SGD", 1_000);

		Response response = movement("/withdrawals", alice, 1_001, null);

		assertThat(response.status() + " " + response.text("code")).isEqualTo("422 INSUFFICIENT_FUNDS");
		assertThat(fixtures.balance(alice)).isEqualTo(1_000);
	}

	@Test
	void allowsDepositsIntoTheHouseFxWallet() {
		UUID liquidity = fixtures.systemAccount("FX_LIQUIDITY", "EUR");
		long before = fixtures.balance(liquidity);

		assertThat(movement("/deposits", liquidity, 500, null).status()).isEqualTo(201);
		assertThat(fixtures.balance(liquidity)).isEqualTo(before + 500);
	}

	@Test
	void reportsAnFxWalletOverdrawAsInsufficientLiquidity() {
		UUID liquidity = fixtures.systemAccount("FX_LIQUIDITY", "EUR");

		Response response = movement("/withdrawals", liquidity, fixtures.balance(liquidity) + 1, null);

		assertThat(response.status() + " " + response.text("code")).isEqualTo("422 INSUFFICIENT_LIQUIDITY");
	}

	@Test
	void rejectsDepositsIntoTheFundingAccount() {
		UUID funding = fixtures.systemAccount("FUNDING", "SGD");

		Response response = movement("/deposits", funding, 100, null);

		assertThat(response.status() + " " + response.text("code")).isEqualTo("422 ACCOUNT_KIND_NOT_ALLOWED");
	}

	@Test
	void storesAndReturnsTheExternalReference() {
		UUID alice = fixtures.customer("Alice", "SGD");
		String ref = "fb-tx-" + UUID.randomUUID();

		Response response = movement("/deposits", alice, 5_000, ref);

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.text("externalRef")).isEqualTo(ref);
		assertThat(api.get("/transactions/" + response.text("id")).text("externalRef")).isEqualTo(ref);
	}

	@Test
	void refusesTheSameExternalReferenceUnderANewKey() {
		UUID alice = fixtures.customer("Alice", "SGD");
		String ref = "fb-tx-" + UUID.randomUUID();
		movement("/deposits", alice, 5_000, ref);

		Response again = movement("/deposits", alice, 5_000, ref);

		assertThat(again.status() + " " + again.text("code")).isEqualTo("409 EXTERNAL_REF_ALREADY_USED");
		assertThat(fixtures.balance(alice)).isEqualTo(5_000);
	}

	@Test
	void rejectsAMalformedExternalReference() {
		UUID alice = fixtures.customer("Alice", "SGD");

		Response response = movement("/deposits", alice, 5_000, "has space");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.json().path("errors").get(0).path("field").asString()).isEqualTo("externalRef");
	}

	/** Optional text is trimmed before it is validated: blank means absent. */
	@Test
	void treatsABlankExternalReferenceAsAbsentAndTrimsAPaddedOne() {
		UUID alice = fixtures.customer("Alice", "SGD");
		String ref = "fb-tx-" + UUID.randomUUID();

		Response blank = movement("/deposits", alice, 100, "");
		Response padded = movement("/deposits", alice, 100, "  " + ref + " ");

		assertThat(blank.status()).isEqualTo(201);
		assertThat(blank.json().path("externalRef").isNull()).isTrue();
		assertThat(padded.status()).isEqualTo(201);
		assertThat(padded.text("externalRef")).isEqualTo(ref);
	}

	@Test
	void replaysTheSameKeyAndBodyWithoutMovingMoneyTwice() {
		UUID alice = fixtures.customer("Alice", "SGD");
		String key = "deposit-" + UUID.randomUUID();
		String body = body(alice, 5_000, "fb-tx-" + UUID.randomUUID());

		Response first = api.post("/deposits", body, Api.IDEMPOTENCY_KEY, key);
		Response replay = api.post("/deposits", body, Api.IDEMPOTENCY_KEY, key);

		assertThat(replay.status()).isEqualTo(201);
		assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
		assertThat(first.header("Idempotent-Replayed")).isNull();
		assertThat(replay.body()).isEqualTo(first.body());
		assertThat(fixtures.balance(alice)).isEqualTo(5_000);
	}

	@Test
	void treatsATrimmedDescriptionAsTheSameRequest() {
		UUID alice = fixtures.customer("Alice", "SGD");
		String key = "deposit-" + UUID.randomUUID();

		Response first = api.post("/deposits", "{\"accountId\": \"" + alice + "\", \"amount\": 100, \"description\": \"top up\"}",
				Api.IDEMPOTENCY_KEY, key);
		Response replay = api.post("/deposits", "{\"accountId\": \"" + alice + "\", \"amount\": 100, \"description\": \"  top up \"}",
				Api.IDEMPOTENCY_KEY, key);

		assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
		assertThat(first.text("description")).isEqualTo("top up");
	}

	@Test
	void letsARejectedRequestsKeyBeUsedAgainLater() {
		UUID alice = fixtures.customer("Alice", "SGD");
		String key = "withdraw-" + UUID.randomUUID();
		String body = body(alice, 500, null);

		Response rejected = api.post("/withdrawals", body, Api.IDEMPOTENCY_KEY, key);
		fixtures.deposit(alice, 1_000);
		Response accepted = api.post("/withdrawals", body, Api.IDEMPOTENCY_KEY, key);

		assertThat(rejected.status()).isEqualTo(422);
		assertThat(accepted.status()).isEqualTo(201);
		assertThat(accepted.header("Idempotent-Replayed")).isNull();
		assertThat(fixtures.balance(alice)).isEqualTo(500);
	}

	@Test
	void hashesDepositsLikeEveryOtherTransaction() {
		UUID alice = fixtures.customer("Alice", "SGD");

		JsonNode tx = movement("/deposits", alice, 100, "fb-tx-" + UUID.randomUUID()).json();

		assertThat(TxHasher.hash(TxHashes.inputOf(tx))).isEqualTo(tx.path("txHash").asString());
	}

	private Response movement(String path, UUID accountId, long amount, String externalRef) {
		return api.post(path, body(accountId, amount, externalRef));
	}

	private static String body(UUID accountId, long amount, String externalRef) {
		return "{\"accountId\": \"" + accountId + "\", \"amount\": " + amount
				+ (externalRef == null ? "" : ", \"externalRef\": \"" + externalRef + "\"") + "}";
	}

}
