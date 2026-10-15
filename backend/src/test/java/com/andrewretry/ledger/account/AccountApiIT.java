package com.andrewretry.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import com.andrewretry.ledger.support.Api;
import com.andrewretry.ledger.support.Api.Response;
import com.andrewretry.ledger.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class AccountApiIT {

	private final Api api;

	AccountApiIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
	}

	// ---- create ------------------------------------------------------------------------------

	@Test
	void createsAnEmptyCustomerAccount() {
		Response created = createAccount("Alice", "SGD");

		assertThat(created.status()).isEqualTo(201);
		JsonNode body = created.json();
		assertThat(created.header("Location")).isEqualTo("/accounts/" + body.path("id").asString());
		assertThat(body.path("name").asString()).isEqualTo("Alice");
		assertThat(body.path("kind").asString()).isEqualTo("CUSTOMER");
		assertThat(body.path("currency").asString()).isEqualTo("SGD");
		assertThat(body.path("balance").path("amount").asLong()).isZero();
		assertThat(body.path("balance").path("currency").asString()).isEqualTo("SGD");
		assertThat(body.path("balance").path("display").asString()).isEqualTo("0.00");
		assertThat(body.path("version").asLong()).isZero();
		assertThat(Instant.parse(body.path("createdAt").asString())).isBefore(Instant.now().plusSeconds(60));
	}

	@Test
	void showsZeroDecimalCurrenciesWithoutAFraction() {
		Response created = createAccount("Kenji", "JPY");

		assertThat(created.json().path("balance").path("display").asString()).isEqualTo("0");
	}

	@Test
	void trimsTheName() {
		assertThat(createAccount("  Dana  ", "USD").json().path("name").asString()).isEqualTo("Dana");
	}

	// ---- get ---------------------------------------------------------------------------------

	@Test
	void returnsACreatedAccountById() {
		String id = createAccount("Bob", "EUR").json().path("id").asString();

		Response fetched = api.get("/accounts/" + id);

		assertThat(fetched.status()).isEqualTo(200);
		assertThat(fetched.json().path("id").asString()).isEqualTo(id);
		assertThat(fetched.json().path("name").asString()).isEqualTo("Bob");
	}

	@Test
	void returnsASystemAccountToo() {
		JsonNode first = api.get("/accounts?size=200").json().path("items");
		String fundingId = null;
		for (JsonNode account : first) {
			if (account.path("kind").asString().equals("FUNDING")) {
				fundingId = account.path("id").asString();
			}
		}
		assertThat(fundingId).isNotNull();

		assertThat(api.get("/accounts/" + fundingId).status()).isEqualTo(200);
	}

	@Test
	void answers404ForAnUnknownAccount() {
		UUID unknown = UUID.randomUUID();

		Response response = api.get("/accounts/" + unknown);

		assertThat(response.status()).isEqualTo(404);
		assertThat(response.header("Content-Type")).startsWith("application/problem+json");
		assertThat(response.text("code")).isEqualTo("ACCOUNT_NOT_FOUND");
		assertThat(response.text("type")).isEqualTo("https://ledger.example/errors/account-not-found");
		assertThat(response.text("status")).isEqualTo("404");
		assertThat(response.text("instance")).isEqualTo("/accounts/" + unknown);
		assertThat(response.text("requestId")).isEqualTo(response.header("X-Request-Id"));
		assertThat(response.body()).doesNotContain("Exception").doesNotContain("\tat ");
	}

	@Test
	void answers400ForAMalformedAccountId() {
		Response response = api.get("/accounts/not-a-uuid");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.text("code")).isEqualTo("VALIDATION_FAILED");
	}

	// ---- validation --------------------------------------------------------------------------

	@Test
	void rejectsABlankName() {
		Response response = api.post("/accounts", "{\"name\": \"   \", \"currency\": \"SGD\"}");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.text("code")).isEqualTo("VALIDATION_FAILED");
		assertThat(response.json().path("errors").get(0).path("field").asString()).isEqualTo("name");
	}

	@Test
	void rejectsANameLongerThan100Characters() {
		Response response = api.post("/accounts", "{\"name\": \"" + "x".repeat(101) + "\", \"currency\": \"SGD\"}");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.json().path("errors").get(0).path("field").asString()).isEqualTo("name");
	}

	@Test
	void rejectsAMissingCurrency() {
		Response response = api.post("/accounts", "{\"name\": \"Eve\"}");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.json().path("errors").get(0).path("field").asString()).isEqualTo("currency");
	}

	@Test
	void rejectsAnUnsupportedCurrencyWith422() {
		Response response = api.post("/accounts", "{\"name\": \"Eve\", \"currency\": \"XYZ\"}");

		assertThat(response.status()).isEqualTo(422);
		assertThat(response.text("code")).isEqualTo("CURRENCY_NOT_SUPPORTED");
	}

	@Test
	void rejectsAnUnknownJsonField() {
		Response response = api.post("/accounts", "{\"name\": \"Eve\", \"currency\": \"SGD\", \"balance\": 100}");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.text("code")).isEqualTo("VALIDATION_FAILED");
	}

	@Test
	void rejectsAMissingBody() {
		Response response = api.post("/accounts", "");

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.text("code")).isEqualTo("VALIDATION_FAILED");
	}

	// ---- list and paging ---------------------------------------------------------------------

	@Test
	void listsAccountsOldestFirstAndPagesConsistently() {
		List<String> mine = List.of(
				createAccount("List 1", "SGD").json().path("id").asString(),
				createAccount("List 2", "USD").json().path("id").asString(),
				createAccount("List 3", "JPY").json().path("id").asString());

		List<String> allIds = new ArrayList<>();
		List<Instant> allCreatedAt = new ArrayList<>();
		int page = 0;
		int totalPages;
		long totalItems;
		do {
			JsonNode body = api.get("/accounts?page=" + page + "&size=5").json();
			assertThat(body.path("page").asInt()).isEqualTo(page);
			assertThat(body.path("size").asInt()).isEqualTo(5);
			body.path("items").forEach(item -> {
				allIds.add(item.path("id").asString());
				allCreatedAt.add(Instant.parse(item.path("createdAt").asString()));
			});
			totalPages = body.path("totalPages").asInt();
			totalItems = body.path("totalItems").asLong();
			assertThat(totalPages).isEqualTo((int) Math.ceil(totalItems / 5.0));
			page++;
		} while (page < totalPages);

		assertThat(allIds).hasSize((int) totalItems).doesNotHaveDuplicates().containsAll(mine);
		assertThat(allCreatedAt).isSorted();
		assertThat(allIds.indexOf(mine.get(0))).isLessThan(allIds.indexOf(mine.get(1)));
		assertThat(allIds.indexOf(mine.get(1))).isLessThan(allIds.indexOf(mine.get(2)));
	}

	@Test
	void defaultsToTheFirstPageOfFifty() {
		JsonNode body = api.get("/accounts").json();

		assertThat(body.path("page").asInt()).isZero();
		assertThat(body.path("size").asInt()).isEqualTo(50);
	}

	@Test
	void rejectsPagingOutsideTheAllowedRange() {
		for (String query : List.of("size=0", "size=201", "page=-1")) {
			Response response = api.get("/accounts?" + query);

			assertThat(response.status()).as(query).isEqualTo(400);
			assertThat(response.text("code")).as(query).isEqualTo("VALIDATION_FAILED");
		}
	}

	// ---- plumbing ----------------------------------------------------------------------------

	@Test
	void echoesASafeRequestId() {
		Response response = api.get("/accounts", "X-Request-Id", "trace-123");

		assertThat(response.header("X-Request-Id")).isEqualTo("trace-123");
	}

	@Test
	void replacesAnUnsafeRequestIdWithAGeneratedOne() {
		Response response = api.get("/accounts", "X-Request-Id", "bad id with spaces");

		assertThat(UUID.fromString(response.header("X-Request-Id"))).isNotNull();
	}

	@Test
	void answersUnknownPathsWithAProblemBody() {
		Response response = api.get("/no-such-path");

		assertThat(response.status()).isEqualTo(404);
		assertThat(response.header("Content-Type")).startsWith("application/problem+json");
		assertThat(response.text("code")).isEqualTo("NOT_FOUND");
		assertThat(response.text("requestId")).isEqualTo(response.header("X-Request-Id"));
	}

	@Test
	void redirectsTheRootToSwaggerUi() {
		Response response = api.get("/");

		assertThat(response.status()).isEqualTo(302);
		assertThat(response.header("Location")).endsWith("/swagger-ui.html");
	}

	private Response createAccount(String name, String currency) {
		return api.post("/accounts", "{\"name\": \"" + name + "\", \"currency\": \"" + currency + "\"}");
	}

}
