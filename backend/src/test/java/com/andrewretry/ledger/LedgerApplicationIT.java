package com.andrewretry.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.andrewretry.ledger.seed.StartupSeeder;
import com.andrewretry.ledger.support.IntegrationTest;

@IntegrationTest
class LedgerApplicationIT {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StartupSeeder seeder;

	@Test
	void appliesMigrationsOneToFive() {
		List<String> versions = jdbc.queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);

		assertThat(versions).containsExactly("1", "2", "3", "4", "5");
	}

	@Test
	void createsOneFundingAndOneFxLiquidityAccountPerCurrency() {
		Integer funding = jdbc.queryForObject("select count(*) from accounts where kind = 'FUNDING'", Integer.class);
		Integer liquidity = jdbc.queryForObject("select count(*) from accounts where kind = 'FX_LIQUIDITY'", Integer.class);

		assertThat(funding).isEqualTo(4);
		assertThat(liquidity).isEqualTo(4);
	}

	/** application-test.yaml seeds 1 000 000 major units per currency. */
	@Test
	void fundsEachFxLiquidityWalletOnceAtStartup() {
		Map<String, Long> expected = Map.of("SGD", 100_000_000L, "USD", 100_000_000L, "EUR", 100_000_000L,
				"JPY", 1_000_000L);

		expected.forEach((currency, amount) -> assertThat(jdbc.queryForList("""
				select p.amount
				  from journal_transactions t
				  join postings p on p.transaction_id = t.id
				  join accounts a on a.id = p.account_id
				 where t.idempotency_key = ? and a.kind = 'FX_LIQUIDITY'""", Long.class, "seed-fxliq-" + currency))
				.as(currency).containsExactly(amount));
	}

	/** Demo data is off in tests; called directly here, twice, as two restarts would. */
	@Test
	void createsTheDemoAccountsWithFixedIdsAndFundsThemOnce() {
		assertThat(StartupSeeder.demoAccountId("Alice")).hasToString("8398f93a-24c5-3661-8e9a-33d54273d824");
		assertThat(StartupSeeder.demoAccountId("Bob")).hasToString("9f75da44-22c8-31e4-9a7d-2bd07518cc4a");
		assertThat(StartupSeeder.demoAccountId("Carol")).hasToString("f5da7969-bb24-307c-ad5f-82b42413fc67");
		assertThat(StartupSeeder.demoAccountId("Kenji")).hasToString("413efdce-9786-318b-be29-69819a82091a");

		seeder.createDemoAccounts();
		seeder.createDemoAccounts();

		assertThat(jdbc.queryForObject("select balance from accounts where id = ?", Long.class,
				StartupSeeder.demoAccountId("Alice"))).isEqualTo(100_000);
		assertThat(jdbc.queryForObject("select balance from accounts where id = ?", Long.class,
				StartupSeeder.demoAccountId("Kenji"))).isEqualTo(50_000);
	}

	/** A restart runs the seeder again; its fixed keys replay, so nothing new is written. */
	@Test
	void seedingAgainChangesNothing() {
		Long chainBefore = jdbc.queryForObject("select chain_length from ledger_state", Long.class);

		seeder.seed();

		assertThat(jdbc.queryForObject("select chain_length from ledger_state", Long.class)).isEqualTo(chainBefore);
		assertThat(jdbc.queryForObject(
				"select count(*) from journal_transactions where idempotency_key like 'seed-fxliq-%'", Integer.class))
				.isEqualTo(4);
	}

}
