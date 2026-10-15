package com.andrewretry.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import com.andrewretry.ledger.support.Api;
import com.andrewretry.ledger.support.Api.Response;
import com.andrewretry.ledger.support.Fixtures;
import com.andrewretry.ledger.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class HistoryApiIT {

	private final Api api;
	private final Fixtures fixtures;

	HistoryApiIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
		this.fixtures = new Fixtures(api);
	}

	@Test
	void listsAnAccountsTransactionsOldestFirstWithRunningBalances() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.funded("Bob", "SGD", 5_000);
		String out = transfer(alice, bob, 2_500, "Lunch split");
		String in = transfer(bob, alice, 1_000, null);

		Response response = api.get("/accounts/" + alice + "/transactions");

		assertThat(response.status()).isEqualTo(200);
		JsonNode body = response.json();
		assertThat(body.path("accountId").asString()).isEqualTo(alice.toString());
		assertThat(body.path("currency").asString()).isEqualTo("SGD");
		assertThat(body.path("totalItems").asLong()).isEqualTo(3);

		JsonNode items = body.path("items");
		assertThat(items.get(0).path("type").asString()).isEqualTo("DEPOSIT");
		assertThat(items.get(0).path("direction").asString()).isEqualTo("CREDIT");
		assertThat(items.get(0).path("balanceAfter").path("amount").asLong()).isEqualTo(10_000);

		assertThat(items.get(1).path("transactionId").asString()).isEqualTo(out);
		assertThat(items.get(1).path("direction").asString()).isEqualTo("DEBIT");
		assertThat(items.get(1).path("amount").path("display").asString()).isEqualTo("25.00");
		assertThat(items.get(1).path("balanceAfter").path("amount").asLong()).isEqualTo(7_500);
		assertThat(items.get(1).path("description").asString()).isEqualTo("Lunch split");

		assertThat(items.get(2).path("transactionId").asString()).isEqualTo(in);
		assertThat(items.get(2).path("direction").asString()).isEqualTo("CREDIT");
		assertThat(items.get(2).path("balanceAfter").path("amount").asLong()).isEqualTo(8_500);

		assertThat(versions(items)).containsExactly(1L, 2L, 3L);
	}

	@Test
	void pagesThroughTheHistory() {
		UUID alice = fixtures.customer("Alice", "SGD");
		for (int i = 0; i < 5; i++) {
			fixtures.deposit(alice, 100);
		}

		JsonNode second = api.get("/accounts/" + alice + "/transactions?page=1&size=2").json();
		JsonNode last = api.get("/accounts/" + alice + "/transactions?page=2&size=2").json();

		assertThat(second.path("totalItems").asLong()).isEqualTo(5);
		assertThat(second.path("totalPages").asInt()).isEqualTo(3);
		assertThat(versions(second.path("items"))).containsExactly(3L, 4L);
		assertThat(versions(last.path("items"))).containsExactly(5L);
		assertThat(last.path("items").get(0).path("balanceAfter").path("amount").asLong()).isEqualTo(500);
	}

	@Test
	void showsAnEmptyHistoryForANewAccount() {
		UUID carol = fixtures.customer("Carol", "USD");

		JsonNode body = api.get("/accounts/" + carol + "/transactions").json();

		assertThat(body.path("items")).isEmpty();
		assertThat(body.path("totalItems").asLong()).isZero();
		assertThat(body.path("totalPages").asInt()).isZero();
	}

	@Test
	void answers404ForAnUnknownAccount() {
		Response response = api.get("/accounts/" + UUID.randomUUID() + "/transactions");

		assertThat(response.status() + " " + response.text("code")).isEqualTo("404 ACCOUNT_NOT_FOUND");
	}

	@Test
	void rejectsPagingOutsideTheAllowedRange() {
		UUID alice = fixtures.customer("Alice", "SGD");

		assertThat(api.get("/accounts/" + alice + "/transactions?size=201").status()).isEqualTo(400);
		assertThat(api.get("/accounts/" + alice + "/transactions?page=-1").status()).isEqualTo(400);
	}

	private String transfer(UUID source, UUID destination, long amount, String description) {
		Response response = api.post("/transactions", "{\"sourceAccountId\": \"" + source + "\", \"destinationAccountId\": \""
				+ destination + "\", \"amount\": " + amount
				+ (description == null ? "" : ", \"description\": \"" + description + "\"") + "}");
		return response.text("id");
	}

	private static List<Long> versions(JsonNode items) {
		List<Long> versions = new ArrayList<>();
		items.forEach(item -> versions.add(item.path("accountVersion").asLong()));
		return versions;
	}

}
