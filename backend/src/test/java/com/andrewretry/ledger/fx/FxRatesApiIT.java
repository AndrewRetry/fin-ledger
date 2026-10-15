package com.andrewretry.ledger.fx;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;

import com.andrewretry.ledger.support.Api;
import com.andrewretry.ledger.support.Api.Response;
import com.andrewretry.ledger.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * Rates and the admin key. Only the JPY -> EUR pair is changed here; other tests rely on the seeded rates of the
 * pairs they use (rates are append-only, so a rate added here stays for the whole test run).
 */
@IntegrationTest
class FxRatesApiIT {

	private final Api api;
	private final int port;

	FxRatesApiIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
		this.port = port;
	}

	@Test
	void listsTheCurrentRateOfEveryPairInOrder() {
		JsonNode rates = api.get("/fx/rates").json();

		assertThat(rates).hasSize(12);
		List<String> pairs = new ArrayList<>();
		rates.forEach(r -> pairs.add(r.path("base").asString() + r.path("quote").asString()));
		assertThat(pairs).isSorted();
		JsonNode sgdUsd = find(rates, "SGD", "USD");
		assertThat(sgdUsd.path("rate").asString()).isEqualTo("0.775");
		assertThat(sgdUsd.path("source").asString()).isEqualTo("SEED");
	}

	@Test
	void addsARateThatBecomesTheCurrentOne() {
		Response added = api.admin("/admin/fx-rates", "{\"base\": \"JPY\", \"quote\": \"EUR\", \"rate\": \"0.006100\"}");

		assertThat(added.status()).isEqualTo(201);
		assertThat(added.text("rate")).isEqualTo("0.0061");
		assertThat(added.text("source")).isEqualTo("MANUAL");
		assertThat(find(api.get("/fx/rates").json(), "JPY", "EUR").path("id").asString()).isEqualTo(added.text("id"));
	}

	@Test
	void requiresTheAdminKey() {
		String body = "{\"base\": \"JPY\", \"quote\": \"EUR\", \"rate\": \"1\"}";

		Response missing = api.post("/admin/fx-rates", body);
		Response wrong = api.post("/admin/fx-rates", body, Api.ADMIN_KEY, "guess");

		assertThat(missing.status() + " " + missing.text("code")).isEqualTo("401 UNAUTHORIZED");
		assertThat(missing.header("Content-Type")).startsWith("application/problem+json");
		assertThat(missing.text("requestId")).isEqualTo(missing.header("X-Request-Id"));
		assertThat(wrong.status()).isEqualTo(401);
	}

	/** Spring routes /admin;x=1/... and /%61dmin/... to the admin endpoints, so the key check must see them too. */
	@ParameterizedTest
	@ValueSource(strings = { "/admin;x=1/fx-rates", "/%61dmin/fx-rates", "/admin/./fx-rates" })
	void requiresTheAdminKeyWhateverTheUrlSpelling(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"base\": \"JPY\", \"quote\": \"EUR\", \"rate\": \"1000\"}"))
				.build();

		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	void rejectsInvalidRates() {
		assertThat(code("{\"base\": \"JPY\", \"quote\": \"EUR\", \"rate\": \"0\"}")).isEqualTo("400 VALIDATION_FAILED");
		assertThat(code("{\"base\": \"JPY\", \"quote\": \"EUR\", \"rate\": \"-1\"}")).isEqualTo("400 VALIDATION_FAILED");
		assertThat(code("{\"base\": \"JPY\", \"quote\": \"EUR\", \"rate\": \"0.1234567890123\"}")).isEqualTo("400 VALIDATION_FAILED");
		assertThat(code("{\"base\": \"JPY\", \"quote\": \"JPY\", \"rate\": \"1\"}")).isEqualTo("400 VALIDATION_FAILED");
		assertThat(code("{\"base\": \"JPY\", \"quote\": \"XYZ\", \"rate\": \"1\"}")).isEqualTo("422 CURRENCY_NOT_SUPPORTED");
	}

	private String code(String body) {
		Response response = api.admin("/admin/fx-rates", body);
		return response.status() + " " + response.text("code");
	}

	private static JsonNode find(JsonNode rates, String base, String quote) {
		for (JsonNode rate : rates) {
			if (rate.path("base").asString().equals(base) && rate.path("quote").asString().equals(quote)) {
				return rate;
			}
		}
		throw new AssertionError("no rate " + base + quote);
	}

}
