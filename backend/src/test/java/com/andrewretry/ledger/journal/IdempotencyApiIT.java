package com.andrewretry.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import com.andrewretry.ledger.support.Api;
import com.andrewretry.ledger.support.Api.Response;
import com.andrewretry.ledger.support.Fixtures;
import com.andrewretry.ledger.support.IntegrationTest;

@IntegrationTest
class IdempotencyApiIT {

	private final Api api;
	private final Fixtures fixtures;

	IdempotencyApiIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
		this.fixtures = new Fixtures(api);
	}

	@Test
	void replaysTheSameKeyAndBodyWithAnIdenticalResponse() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		String key = key();

		Response first = transfer(alice, bob, 2_500, key);
		Response replay = transfer(alice, bob, 2_500, key);

		assertThat(first.status()).isEqualTo(201);
		assertThat(replay.status()).isEqualTo(201);
		assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
		assertThat(replay.body()).isEqualTo(first.body());
		assertThat(fixtures.balance(alice)).isEqualTo(7_500);
		assertThat(fixtures.balance(bob)).isEqualTo(2_500);
	}

	@Test
	void rejectsTheSameKeyWithADifferentBody() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		String key = key();
		transfer(alice, bob, 2_500, key);

		Response different = transfer(alice, bob, 9_999, key);

		assertThat(different.status() + " " + different.text("code")).isEqualTo("422 IDEMPOTENCY_KEY_REUSED");
		assertThat(fixtures.balance(alice)).isEqualTo(7_500);
	}

	@Test
	void letsARejectedRequestsKeyWorkOnceTheAccountIsFunded() {
		UUID alice = fixtures.customer("Alice", "SGD");
		UUID bob = fixtures.customer("Bob", "SGD");
		String key = key();

		Response rejected = transfer(alice, bob, 2_500, key);
		fixtures.deposit(alice, 10_000);
		Response accepted = transfer(alice, bob, 2_500, key);

		assertThat(rejected.text("code")).isEqualTo("INSUFFICIENT_FUNDS");
		assertThat(accepted.status()).isEqualTo(201);
		assertThat(accepted.header("Idempotent-Replayed")).isNull();
		assertThat(fixtures.balance(bob)).isEqualTo(2_500);
	}

	/** Step 3 runs before the funds check: a retry of a transfer that succeeded is replayed, not re-judged. */
	@Test
	void replaysASuccessfulTransferEvenAfterTheMoneyWasSpent() {
		UUID alice = fixtures.funded("Alice", "SGD", 2_500);
		UUID bob = fixtures.customer("Bob", "SGD");
		String key = key();
		Response first = transfer(alice, bob, 2_500, key);
		fixtures.deposit(bob, 1);

		Response retry = transfer(alice, bob, 2_500, key);

		assertThat(retry.status()).isEqualTo(201);
		assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");
		assertThat(retry.body()).isEqualTo(first.body());
	}

	@Test
	void acceptsAQuotedKeyAsTheSameKey() {
		UUID alice = fixtures.funded("Alice", "SGD", 10_000);
		UUID bob = fixtures.customer("Bob", "SGD");
		String key = key();
		transfer(alice, bob, 100, key);

		Response quoted = transfer(alice, bob, 100, "\"" + key + "\"");

		assertThat(quoted.header("Idempotent-Replayed")).isEqualTo("true");
	}

	private Response transfer(UUID source, UUID destination, long amount, String key) {
		return api.post("/transactions", "{\"sourceAccountId\": \"" + source + "\", \"destinationAccountId\": \""
				+ destination + "\", \"amount\": " + amount + "}", Api.IDEMPOTENCY_KEY, key);
	}

	private static String key() {
		return "idem-" + UUID.randomUUID();
	}

}
