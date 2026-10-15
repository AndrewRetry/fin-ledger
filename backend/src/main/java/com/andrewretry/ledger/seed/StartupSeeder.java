package com.andrewretry.ledger.seed;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.andrewretry.ledger.account.AccountKind;
import com.andrewretry.ledger.account.AccountRepository;
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

	public StartupSeeder(LedgerProperties properties, Currencies currencies, AccountRepository accounts,
			LedgerCommandService commands) {
		this.properties = properties;
		this.currencies = currencies;
		this.accounts = accounts;
		this.commands = commands;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void seed() {
		fundFxLiquidity();
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
