package com.andrewretry.ledger.seed;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.andrewretry.ledger.account.AccountKind;
import com.andrewretry.ledger.account.AccountRepository;
import com.andrewretry.ledger.account.AccountService;
import com.andrewretry.ledger.common.Currencies;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.config.LedgerProperties;
import com.andrewretry.ledger.journal.LedgerCommandService;

/**
 * Puts starting money into the ledger when the app is ready, through the normal deposit path, so every cent is in
 * the journal and the hash chain. Each deposit has a fixed idempotency key, so on a restart it is replayed, not
 * repeated: the money goes in exactly once.
 */
@Component
public class StartupSeeder {

	private static final Logger log = LoggerFactory.getLogger(StartupSeeder.class);

	private final LedgerProperties properties;
	private final Currencies currencies;
	private final AccountRepository accounts;
	private final LedgerCommandService commands;
	private final AccountService accountService;

	public StartupSeeder(LedgerProperties properties, Currencies currencies, AccountRepository accounts,
			LedgerCommandService commands, AccountService accountService) {
		this.properties = properties;
		this.currencies = currencies;
		this.accounts = accounts;
		this.commands = commands;
		this.accountService = accountService;
	}

	/** The demo customers: name, currency, first deposit in minor units. Their ids are derived from the name. */
	private record DemoAccount(String name, String currency, long deposit) {
	}

	private static final List<DemoAccount> DEMO_ACCOUNTS = List.of(
			new DemoAccount("Alice", "SGD", 100_000),   // SGD 1,000.00
			new DemoAccount("Bob", "SGD", 25_000),      // SGD 250.00
			new DemoAccount("Carol", "USD", 50_000),    // USD 500.00
			new DemoAccount("Kenji", "JPY", 50_000));   // JPY 50,000

	@EventListener(ApplicationReadyEvent.class)
	public void seed() {
		fundFxLiquidity();
		if (properties.seed().demoData()) {
			createDemoAccounts();
		}
	}

	/** Fixed id for a demo account, e.g. Alice = 8398f93a-24c5-3661-8e9a-33d54273d824 on every machine. */
	public static UUID demoAccountId(String name) {
		return UUID.nameUUIDFromBytes(("ledger-demo:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
	}

	/** Step 2: Alice, Bob, Carol and Kenji, created if missing and funded once (key seed-demo-name). */
	public void createDemoAccounts() {
		for (DemoAccount demo : DEMO_ACCOUNTS) {
			UUID id = demoAccountId(demo.name());
			accountService.createIfMissing(id, demo.name(), demo.currency());
			depositOnce("seed-demo-" + demo.name().toLowerCase(Locale.ROOT), id, demo.deposit(), "Demo opening balance");
		}
	}

	/** Step 1: each currency's house FX wallet gets {@code ledger.seed.fx-liquidity-major-units} of that currency. */
	void fundFxLiquidity() {
		long majorUnits = properties.seed().fxLiquidityMajorUnits();
		if (majorUnits <= 0) {
			return;
		}
		for (String currency : currencies.codes()) {
			long amount = toMinorUnits(majorUnits, currencies.minorUnits(currency));
			UUID wallet = accounts.findSystemAccount(AccountKind.FX_LIQUIDITY, currency).id();
			depositOnce("seed-fxliq-" + currency, wallet, amount, "FX liquidity seed");
		}
	}

	/** A failure here (e.g. an integrity hold) is reported but must not stop the application from starting. */
	private void depositOnce(String key, UUID accountId, long amount, String description) {
		try {
			commands.deposit(key, accountId, amount, description, null);
		}
		catch (LedgerException e) {
			log.warn("Seed deposit {} skipped: {} {}", key, e.code(), e.getMessage());
		}
	}

	/** 1 000 000 major units of SGD (2 decimals) = 100 000 000 minor units; of JPY (0 decimals) = 1 000 000. */
	private static long toMinorUnits(long majorUnits, int decimals) {
		long amount = majorUnits;
		for (int i = 0; i < decimals; i++) {
			amount = Math.multiplyExact(amount, 10);
		}
		return amount;
	}

}
