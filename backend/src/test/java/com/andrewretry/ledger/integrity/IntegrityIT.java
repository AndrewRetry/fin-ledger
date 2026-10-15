package com.andrewretry.ledger.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.andrewretry.ledger.support.Api;
import com.andrewretry.ledger.support.Api.Response;
import com.andrewretry.ledger.support.Fixtures;
import com.andrewretry.ledger.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class IntegrityIT {

	private final Api api;
	private final Fixtures fixtures;

	@Autowired
	IntegrityHoldService holds;

	IntegrityIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
		this.fixtures = new Fixtures(api);
	}

	@Test
	void findsTheLedgerCleanAfterAMixedWorkload() {
		UUID alice = fixtures.funded("Alice", "SGD", 50_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		UUID carol = fixtures.customer("Carol", "USD");
		String transfer = transfer(alice, bob, 2_500).text("id");
		transfer(alice, carol, 10_000);                                                 // FX
		api.post("/withdrawals", "{\"accountId\": \"" + alice + "\", \"amount\": 1000}");
		api.post("/transactions/" + transfer + "/reversals", "{\"reason\": \"dispute\"}");

		Response response = api.post("/integrity/checks", "");

		assertThat(response.status()).isEqualTo(200);
		JsonNode report = response.json();
		assertThat(report.path("kind").asString()).isEqualTo("INTEGRITY");
		assertThat(report.path("status").asString()).isEqualTo("CLEAN");
		assertThat(report.path("discrepancies")).isEmpty();
		assertThat(report.path("integrityHold").asBoolean()).isFalse();
		assertThat(report.path("checks")).hasSize(7);
		report.path("checks").forEach(check -> assertThat(check.path("discrepancies").asLong()).isZero());
		assertThat(report.path("moneySupply")).hasSize(4);
		report.path("moneySupply").forEach(line -> assertThat(line.path("matches").asBoolean()).isTrue());
	}

	@Test
	void reportsAValidChainEndingAtTheLedgerHead() {
		UUID alice = fixtures.funded("Alice", "SGD", 1_000);
		JsonNode last = transfer(alice, fixtures.customer("Bob", "SGD"), 100).json();

		JsonNode chain = api.post("/integrity/checks", "").json().path("chain");
		JsonNode head = api.get("/ledger/head").json();

		assertThat(chain.path("valid").asBoolean()).isTrue();
		assertThat(chain.path("headHash").asString()).isEqualTo(head.path("headHash").asString());
		assertThat(chain.path("length").asLong()).isEqualTo(head.path("chainLength").asLong());
		assertThat(head.path("headHash").asString()).isEqualTo(last.path("txHash").asString());
	}

	@Test
	void storesTheRunSoItCanBeReadBack() {
		JsonNode report = api.post("/integrity/checks", "").json();

		Response stored = api.get("/integrity/runs/" + report.path("runId").asString());

		assertThat(stored.status()).isEqualTo(200);
		JsonNode run = stored.json();
		assertThat(run.path("status").asString()).isEqualTo("CLEAN");
		assertThat(run.path("checkedTransactions").asInt()).isEqualTo(report.path("checkedTransactions").asInt());
		assertThat(run.path("moneySupply").isNull()).isTrue();
		assertThat(run.path("chain").isNull()).isTrue();
		assertThat(run.path("accounts").isNull()).isTrue();
	}

	@Test
	void answers404ForAnUnknownRun() {
		Response response = api.get("/integrity/runs/" + UUID.randomUUID());

		assertThat(response.status() + " " + response.text("code")).isEqualTo("404 RUN_NOT_FOUND");
	}

	/** Turned on directly here; TamperDetectionIT turns it on the real way, by breaking data. */
	@Test
	void holdStopsWritesButNotReadsUntilAnAdminReleasesIt() {
		UUID alice = fixtures.funded("Alice", "SGD", 1_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		holds.hold("test hold");
		try {
			Response write = transfer(alice, bob, 100);
			Response read = api.get("/accounts/" + alice);
			Response status = api.get("/admin/integrity-hold", Api.ADMIN_KEY, Api.TEST_ADMIN_KEY);

			assertThat(write.status() + " " + write.text("code")).isEqualTo("503 LEDGER_INTEGRITY_HOLD");
			assertThat(read.status()).isEqualTo(200);
			assertThat(read.header("X-Ledger-Integrity")).isEqualTo("HOLD");
			assertThat(status.json().path("held").asBoolean()).isTrue();
			assertThat(status.text("reason")).isEqualTo("test hold");
			assertThat(api.get("/actuator/health").json().path("status").asString()).isEqualTo("DOWN");
		}
		finally {
			Response released = api.admin("/admin/integrity-hold/release", "{\"reason\": \"test over\"}");
			assertThat(released.json().path("held").asBoolean()).isFalse();
		}
		assertThat(api.get("/accounts/" + alice).header("X-Ledger-Integrity")).isNull();
		assertThat(transfer(alice, bob, 100).status()).isEqualTo(201);
	}

	@Test
	void adminHoldEndpointsNeedTheAdminKey() {
		assertThat(api.get("/admin/integrity-hold").status()).isEqualTo(401);
		assertThat(api.post("/admin/integrity-hold/release", "{\"reason\": \"x\"}").status()).isEqualTo(401);
	}

	private Response transfer(UUID source, UUID destination, long amount) {
		return api.post("/transactions", "{\"sourceAccountId\": \"" + source + "\", \"destinationAccountId\": \""
				+ destination + "\", \"amount\": " + amount + "}");
	}

}
