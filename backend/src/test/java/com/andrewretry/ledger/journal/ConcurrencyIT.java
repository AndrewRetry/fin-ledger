package com.andrewretry.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.andrewretry.ledger.account.AccountService;
import com.andrewretry.ledger.common.error.ErrorCode;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.support.IntegrationTest;

/**
 * Many writers at once, straight against the service. Asserts outcomes and invariants, never timing: no lost
 * updates on a hot account, no deadlocks between opposite transfers, and the books still balance afterwards.
 */
@IntegrationTest
class ConcurrencyIT {

	@Autowired
	LedgerCommandService commands;

	@Autowired
	AccountService accounts;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void hotAccountNeverLosesAnUpdate() throws Exception {
		UUID hot = funded("Hot", 50_000);
		UUID sink = customer("Sink");
		AtomicInteger succeeded = new AtomicInteger();
		AtomicInteger insufficient = new AtomicInteger();
		List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

		// 50 threads x 20 transfers of 100 = 1 000 attempts against a balance that covers exactly 500.
		runTogether(50, thread -> {
			for (int i = 0; i < 20; i++) {
				try {
					commands.transfer(key(), hot, sink, 100, null, null);
					succeeded.incrementAndGet();
				}
				catch (LedgerException e) {
					if (e.code() == ErrorCode.INSUFFICIENT_FUNDS) {
						insufficient.incrementAndGet();
					}
					else {
						unexpected.add(e);
					}
				}
				catch (RuntimeException e) {
					unexpected.add(e);
				}
			}
		});

		assertThat(unexpected).isEmpty();
		assertThat(succeeded).hasValue(500);
		assertThat(insufficient).hasValue(500);
		assertThat(balance(hot)).isZero();
		assertThat(balance(sink)).isEqualTo(50_000);
		assertBooksBalance();
	}

	@Test
	void oppositeTransfersNeverDeadlock() throws Exception {
		UUID a = funded("A", 1_000);
		UUID b = funded("B", 1_000);
		AtomicInteger succeeded = new AtomicInteger();
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

		// 50 threads x 10 transfers of 1: even threads send A -> B, odd threads B -> A. Each side can always pay.
		runTogether(50, thread -> {
			UUID from = thread % 2 == 0 ? a : b;
			UUID to = thread % 2 == 0 ? b : a;
			for (int i = 0; i < 10; i++) {
				try {
					commands.transfer(key(), from, to, 1, null, null);
					succeeded.incrementAndGet();
				}
				catch (RuntimeException e) {
					failures.add(e);
				}
			}
		});

		assertThat(failures).isEmpty();
		assertThat(succeeded).hasValue(500);
		assertThat(balance(a)).isEqualTo(1_000);
		assertThat(balance(b)).isEqualTo(1_000);
		assertBooksBalance();
	}

	/** Starts every thread at the same instant behind a latch, then waits for all of them. */
	private static void runTogether(int threads, IntConsumer work) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> running = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				int thread = t;
				running.add(executor.submit(() -> {
					start.await();
					work.accept(thread);
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : running) {
				future.get(2, TimeUnit.MINUTES);
			}
		}
	}

	/**
	 * The provided integrity queries (PLAN.md §7.8) must find nothing wrong. IntegrityService runs them from P5;
	 * until then the test runs them itself.
	 */
	private void assertBooksBalance() throws IOException {
		for (String check : List.of("trial_balance", "transaction_balance", "account_balances")) {
			assertThat(jdbc.queryForList(sql(check))).as(check).isEmpty();
		}
		for (Map<String, Object> row : jdbc.queryForList(sql("money_supply"))) {
			assertThat(row.get("actual")).as("money supply of " + row.get("currency")).isEqualTo(row.get("expected"));
		}
	}

	private static String sql(String check) throws IOException {
		return new ClassPathResource("sql/checks/" + check + ".sql").getContentAsString(StandardCharsets.UTF_8);
	}

	private UUID customer(String name) {
		return accounts.create(name, "SGD").id();
	}

	private UUID funded(String name, long amount) {
		UUID id = customer(name);
		commands.deposit(key(), id, amount, null, null);
		return id;
	}

	private long balance(UUID id) {
		return accounts.get(id).balance().amount();
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

}
