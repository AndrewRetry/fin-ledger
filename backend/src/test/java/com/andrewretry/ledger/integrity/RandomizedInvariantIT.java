package com.andrewretry.ledger.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.andrewretry.ledger.account.AccountKind;
import com.andrewretry.ledger.account.AccountRepository;
import com.andrewretry.ledger.account.AccountService;
import com.andrewretry.ledger.common.Currencies;
import com.andrewretry.ledger.common.error.ErrorCode;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.fx.FxConversion;
import com.andrewretry.ledger.fx.FxRateService;
import com.andrewretry.ledger.integrity.dto.IntegrityReport;
import com.andrewretry.ledger.journal.CommandResult;
import com.andrewretry.ledger.journal.LedgerCommandService;
import com.andrewretry.ledger.support.IntegrationTest;

/**
 * Random deposits, withdrawals, transfers, FX transfers and reversals, replayed against a tiny in-memory model of
 * the balances. For every operation the model predicts the outcome (success or the exact rejection); at the end
 * every balance must equal the model and the integrity run must be clean. Fixed seeds keep it repeatable.
 */
@IntegrationTest
class RandomizedInvariantIT {

	private static final int OPERATIONS = 150;

	@Autowired
	LedgerCommandService commands;

	@Autowired
	AccountService accounts;

	@Autowired
	AccountRepository accountRepository;

	@Autowired
	FxRateService fxRates;

	@Autowired
	Currencies currencies;

	@Autowired
	IntegrityService integrity;

	/** A successful transaction and how it changed the model's accounts, so it can be reversed later. */
	private record Posted(UUID id, Map<UUID, Long> changes) {
	}

	private final Map<UUID, String> currencyOf = new HashMap<>();
	private final Map<UUID, Long> balance = new HashMap<>();
	private final List<Posted> reversible = new ArrayList<>();
	private final Set<UUID> reversed = new HashSet<>();

	@ParameterizedTest(name = "seed {0}")
	@ValueSource(longs = { 1, 42, 20261015 })
	void randomOperationsKeepEveryBalanceExact(long seed) {
		Random random = new Random(seed);
		List<UUID> sgd = List.of(open("SGD"), open("SGD"));
		List<UUID> all = List.of(sgd.get(0), sgd.get(1), open("USD"), open("JPY"));
		// Keep the house's yen wallet well stocked: USD/SGD/EUR -> JPY transfers pay out of it.
		commands.deposit(UUID.randomUUID().toString(),
				accountRepository.findSystemAccount(AccountKind.FX_LIQUIDITY, "JPY").id(), 1_000_000, null, null);

		for (int i = 0; i < OPERATIONS; i++) {
			switch (random.nextInt(5)) {
				case 0 -> deposit(pick(random, all), 1 + random.nextInt(100_000));
				case 1 -> withdraw(pick(random, all), 1 + random.nextInt(50_000));
				case 2 -> transfer(sgd.get(i % 2), sgd.get((i + 1) % 2), 1 + random.nextInt(30_000));
				case 3 -> fxTransfer(random, all, 1 + random.nextInt(10_000));
				default -> reverse(random);
			}
		}

		for (UUID account : all) {
			assertThat(accounts.get(account).balance().amount()).as("balance of " + account).isEqualTo(balance.get(account));
		}
		IntegrityReport report = integrity.run();
		assertThat(report.discrepancies()).isEmpty();
	}

	private void deposit(UUID account, long amount) {
		expect(null, () -> commands.deposit(key(), account, amount, null, null), Map.of(account, amount));
	}

	private void withdraw(UUID account, long amount) {
		ErrorCode expected = amount > balance.get(account) ? ErrorCode.INSUFFICIENT_FUNDS : null;
		expect(expected, () -> commands.withdraw(key(), account, amount, null, null), Map.of(account, -amount));
	}

	private void transfer(UUID from, UUID to, long amount) {
		ErrorCode expected = amount > balance.get(from) ? ErrorCode.INSUFFICIENT_FUNDS : null;
		expect(expected, () -> commands.transfer(key(), from, to, amount, null, null), Map.of(from, -amount, to, amount));
	}

	private void fxTransfer(Random random, List<UUID> all, long amount) {
		UUID from = pick(random, all);
		UUID to = pick(random, all);
		while (currencyOf.get(to).equals(currencyOf.get(from))) {
			to = pick(random, all);
		}
		String fromCcy = currencyOf.get(from);
		String toCcy = currencyOf.get(to);
		long converted = FxConversion.convert(amount, fxRates.latest(fromCcy, toCcy).getRate(),
				currencies.minorUnits(fromCcy), currencies.minorUnits(toCcy));
		ErrorCode expected = converted == 0 ? ErrorCode.FX_AMOUNT_TOO_SMALL
				: amount > balance.get(from) ? ErrorCode.INSUFFICIENT_FUNDS : null;
		UUID target = to;
		expect(expected, () -> commands.transfer(key(), from, target, amount, null, null),
				Map.of(from, -amount, to, converted));
	}

	private void reverse(Random random) {
		List<Posted> open = reversible.stream().filter(p -> !reversed.contains(p.id())).toList();
		if (open.isEmpty()) {
			return;
		}
		Posted original = open.get(random.nextInt(open.size()));
		Map<UUID, Long> undo = new HashMap<>();
		original.changes().forEach((account, change) -> undo.put(account, -change));
		boolean someoneSpentIt = undo.entrySet().stream().anyMatch(e -> balance.get(e.getKey()) + e.getValue() < 0);
		ErrorCode expected = someoneSpentIt ? ErrorCode.INSUFFICIENT_FUNDS : null;
		if (expect(expected, () -> commands.reverse(key(), original.id(), "randomized"), undo) != null) {
			reversed.add(original.id());
		}
	}

	/**
	 * Runs the command and checks it ended exactly as the model predicted: success ({@code expected == null}),
	 * in which case the model applies {@code changes}, or the predicted rejection, in which case nothing changes.
	 */
	private CommandResult expect(ErrorCode expected, Supplier<CommandResult> command, Map<UUID, Long> changes) {
		CommandResult result = null;
		ErrorCode actual = null;
		try {
			result = command.get();
		}
		catch (LedgerException e) {
			actual = e.code();
		}
		assertThat(actual).isEqualTo(expected);
		if (result != null) {
			changes.forEach((account, change) -> balance.merge(account, change, Long::sum));
			if (result.view().reversesId() == null) {
				reversible.add(new Posted(result.view().id(), changes));
			}
		}
		return result;
	}

	private UUID open(String currency) {
		UUID id = accounts.create("Random " + currency, currency).id();
		currencyOf.put(id, currency);
		balance.put(id, 0L);
		return id;
	}

	private static UUID pick(Random random, List<UUID> accounts) {
		return accounts.get(random.nextInt(accounts.size()));
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

}
