package com.andrewretry.ledger.fx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import com.andrewretry.ledger.journal.TxHasher;
import com.andrewretry.ledger.support.Api;
import com.andrewretry.ledger.support.Api.Response;
import com.andrewretry.ledger.support.Fixtures;
import com.andrewretry.ledger.support.IntegrationTest;
import com.andrewretry.ledger.support.TxHashes;

import tools.jackson.databind.JsonNode;

/**
 * Cross-currency transfers through the house FX wallets. Only EUR -> SGD gets a new rate here (FxRatesApiIT uses
 * JPY -> EUR), so SGD -> USD stays at the seeded 0.775 for every test.
 */
@IntegrationTest
class FxTransferApiIT {

	private final Api api;
	private final Fixtures fixtures;

	FxTransferApiIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
		this.fixtures = new Fixtures(api);
	}

	@Test
	void convertsSgdToUsdAtTheSeededRateThroughTheHouseWallets() {
		UUID alice = fixtures.funded("Alice", "SGD", 100_000);
		UUID carol = fixtures.customer("Carol", "USD");
		UUID houseSgd = fixtures.systemAccount("FX_LIQUIDITY", "SGD");
		UUID houseUsd = fixtures.systemAccount("FX_LIQUIDITY", "USD");
		long houseSgdBefore = fixtures.balance(houseSgd);
		long houseUsdBefore = fixtures.balance(houseUsd);

		Response response = transfer(alice, carol, 10_000);

		assertThat(response.status()).isEqualTo(201);
		JsonNode tx = response.json();
		assertThat(tx.path("type").asString()).isEqualTo("FX_TRANSFER");
		assertThat(tx.path("postings")).hasSize(4);
		assertThat(tx.path("fx").path("base").asString()).isEqualTo("SGD");
		assertThat(tx.path("fx").path("quote").asString()).isEqualTo("USD");
		assertThat(tx.path("fx").path("rate").asString()).isEqualTo("0.775");
		assertThat(tx.path("fx").path("source").asString()).isEqualTo("SEED");
		assertThat(fixtures.balance(alice)).isEqualTo(90_000);                       // SGD 100.00 out
		assertThat(fixtures.balance(carol)).isEqualTo(7_750);                        // USD 77.50 in
		assertThat(fixtures.balance(houseSgd)).isEqualTo(houseSgdBefore + 10_000);   // the house took the SGD
		assertThat(fixtures.balance(houseUsd)).isEqualTo(houseUsdBefore - 7_750);    // and paid out the USD
		assertThat(TxHasher.hash(TxHashes.inputOf(tx))).isEqualTo(tx.path("txHash").asString());
	}

	@Test
	void usesARateAddedByAnAdminForTheNextTransfer() {
		Response added = api.admin("/admin/fx-rates", "{\"base\": \"EUR\", \"quote\": \"SGD\", \"rate\": \"1.5\"}");
		UUID eve = fixtures.funded("Eve", "EUR", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");

		JsonNode tx = transfer(eve, bob, 10_000).json();

		assertThat(tx.path("fx").path("rateId").asString()).isEqualTo(added.text("id"));
		assertThat(tx.path("fx").path("source").asString()).isEqualTo("MANUAL");
		assertThat(fixtures.balance(bob)).isEqualTo(15_000);
	}

	@Test
	void showsTheRateOnTheStoredTransactionAndOnAReplay() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID carol = fixtures.customer("Carol", "USD");
		String key = "fx-" + UUID.randomUUID();
		Response first = transfer(alice, carol, 1_000, key);

		Response replay = transfer(alice, carol, 1_000, key);
		Response fetched = api.get("/transactions/" + first.text("id"));

		assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
		assertThat(replay.body()).isEqualTo(first.body());
		assertThat(fetched.json().path("fx").path("rate").asString()).isEqualTo("0.775");
	}

	@Test
	void rejectsATransferTheHouseCannotPayOut() {
		UUID houseJpy = fixtures.systemAccount("FX_LIQUIDITY", "JPY");
		long houseHolds = fixtures.balance(houseJpy);
		// USD -> JPY is about 151.5 yen per dollar, so this many cents converts to more yen than the house holds.
		UUID carol = fixtures.funded("Carol", "USD", houseHolds);
		UUID kenji = fixtures.customer("Kenji", "JPY");

		Response response = transfer(carol, kenji, houseHolds);

		assertThat(response.status() + " " + response.text("code")).isEqualTo("422 INSUFFICIENT_LIQUIDITY");
		assertThat(fixtures.balance(carol)).isEqualTo(houseHolds);
		assertThat(fixtures.balance(houseJpy)).isEqualTo(houseHolds);
	}

	@Test
	void rejectsAnAmountTooSmallToConvert() {
		UUID kenji = fixtures.funded("Kenji", "JPY", 10);
		UUID carol = fixtures.customer("Carol", "USD");

		Response response = transfer(kenji, carol, 1);   // JPY 1 at 0.0066 = USD 0.0066, less than one cent

		assertThat(response.status() + " " + response.text("code")).isEqualTo("422 FX_AMOUNT_TOO_SMALL");
		assertThat(fixtures.balance(kenji)).isEqualTo(10);
	}

	private Response transfer(UUID source, UUID destination, long amount, String... key) {
		String body = "{\"sourceAccountId\": \"" + source + "\", \"destinationAccountId\": \"" + destination
				+ "\", \"amount\": " + amount + "}";
		return key.length == 0 ? api.post("/transactions", body) : api.post("/transactions", body, Api.IDEMPOTENCY_KEY, key[0]);
	}

}
