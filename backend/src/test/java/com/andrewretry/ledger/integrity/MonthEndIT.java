package com.andrewretry.ledger.integrity;

import static com.andrewretry.ledger.support.FixedClockConfig.thisMonth;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.andrewretry.ledger.support.Api;
import com.andrewretry.ledger.support.Api.Response;
import com.andrewretry.ledger.support.FixedClockConfig;
import com.andrewretry.ledger.support.Fixtures;
import com.andrewretry.ledger.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/** Runs on the 5th of next month (FixedClockConfig), in its own database. Only one test closes this month. */
@IntegrationTest
@Import(FixedClockConfig.class)
class MonthEndIT {

	private final Api api;
	private final Fixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	MonthEndIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
		this.fixtures = new Fixtures(api);
	}

	@Test
	void closesTheEndedMonthOnceAndFreezesOneSnapshotPerAccount() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		api.post("/transactions", "{\"sourceAccountId\": \"" + alice + "\", \"destinationAccountId\": \"" + bob
				+ "\", \"amount\": 2500}");

		JsonNode first = close(thisMonth().toString()).json();

		assertThat(first.path("kind").asString()).isEqualTo("MONTH_END");
		assertThat(first.path("period").asString()).isEqualTo(thisMonth().toString());
		assertThat(first.path("provisional").asBoolean()).isFalse();
		assertThat(first.path("status").asString()).isEqualTo("CLEAN");
		assertThat(first.path("moneySupply").isNull()).isTrue();
		assertThat(first.path("chain").isNull()).isTrue();
		assertThat(first.path("checks")).hasSize(3);
		int accounts = first.path("checkedAccounts").asInt();
		assertThat(first.path("accounts")).hasSize(accounts);
		assertThat(first.path("snapshotsWritten").asInt()).isEqualTo(accounts);
		assertThat(jdbc.queryForObject("select count(*) from account_period_balances where period = ?", Integer.class,
				thisMonth().atDay(1))).isEqualTo(accounts);

		JsonNode aliceLine = line(first, alice);
		assertThat(aliceLine.path("opening").path("amount").asLong()).isZero();
		assertThat(aliceLine.path("credits").path("amount").asLong()).isEqualTo(10_000);
		assertThat(aliceLine.path("debits").path("amount").asLong()).isEqualTo(2_500);
		assertThat(aliceLine.path("expectedClosing").path("amount").asLong()).isEqualTo(7_500);
		assertThat(aliceLine.path("actualClosing").path("amount").asLong()).isEqualTo(7_500);
		assertThat(aliceLine.path("matches").asBoolean()).isTrue();

		JsonNode again = close(thisMonth().toString()).json();

		assertThat(again.path("status").asString()).isEqualTo("CLEAN");
		assertThat(again.path("snapshotsWritten").asInt()).isZero();

		JsonNode stored = api.get("/integrity/runs/" + first.path("runId").asString()).json();
		assertThat(stored.path("kind").asString()).isEqualTo("MONTH_END");
		assertThat(stored.path("snapshotsWritten").asInt()).isEqualTo(accounts);
		assertThat(stored.path("accounts").isNull()).isTrue();
	}

	@Test
	void treatsAMonthThatHasNotEndedAsProvisionalAndFreezesNothing() {
		JsonNode report = close(thisMonth().plusMonths(1).toString()).json();

		assertThat(report.path("provisional").asBoolean()).isTrue();
		assertThat(report.path("status").asString()).isEqualTo("CLEAN");
		assertThat(report.path("snapshotsWritten").asInt()).isZero();
	}

	@Test
	void rejectsAMonthThatHasNotStarted() {
		Response response = close(thisMonth().plusMonths(2).toString());

		assertThat(response.status() + " " + response.text("code")).isEqualTo("400 VALIDATION_FAILED");
	}

	@Test
	void rejectsAMissingOrMalformedPeriod() {
		assertThat(api.post("/reconciliations/month-end", "").status()).isEqualTo(400);
		assertThat(close("2026-13").status()).isEqualTo(400);
		assertThat(close("October").status()).isEqualTo(400);
	}

	private Response close(String period) {
		return api.post("/reconciliations/month-end?period=" + period, "");
	}

	private static JsonNode line(JsonNode report, UUID accountId) {
		for (JsonNode line : report.path("accounts")) {
			if (line.path("accountId").asString().equals(accountId.toString())) {
				return line;
			}
		}
		throw new AssertionError("no month-end line for " + accountId);
	}

}
