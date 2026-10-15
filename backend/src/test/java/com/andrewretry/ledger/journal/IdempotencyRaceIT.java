package com.andrewretry.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.andrewretry.ledger.account.AccountService;
import com.andrewretry.ledger.support.IntegrationTest;

/**
 * Twenty copies of one request at the same instant. They lock the same accounts, so they queue at step 2; the first
 * commits, and every later one finds its row at step 3 and replays it.
 */
@IntegrationTest
class IdempotencyRaceIT {

	@Autowired
	LedgerCommandService commands;

	@Autowired
	AccountService accounts;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void identicalRequestsReleasedTogetherMoveMoneyOnce() throws Exception {
		UUID alice = accounts.create("Alice", "SGD").id();
		UUID bob = accounts.create("Bob", "SGD").id();
		commands.deposit(UUID.randomUUID().toString(), alice, 10_000, null, null);
		String key = "race-" + UUID.randomUUID();

		CountDownLatch start = new CountDownLatch(1);
		List<CommandResult> results = new ArrayList<>();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<CommandResult>> running = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				running.add(executor.submit(() -> {
					start.await();
					return commands.transfer(key, alice, bob, 2_500, null, "same request");
				}));
			}
			start.countDown();
			for (Future<CommandResult> future : running) {
				results.add(future.get(1, TimeUnit.MINUTES));
			}
		}

		assertThat(results.stream().map(r -> r.view().id()).distinct()).hasSize(1);
		assertThat(results.stream().filter(CommandResult::replayed)).hasSize(19);
		assertThat(jdbc.queryForObject("select count(*) from journal_transactions where idempotency_key = ?",
				Integer.class, key)).isEqualTo(1);
		assertThat(accounts.get(alice).balance().amount()).isEqualTo(7_500);
		assertThat(accounts.get(bob).balance().amount()).isEqualTo(2_500);
	}

}
