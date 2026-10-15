package com.andrewretry.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.andrewretry.ledger.support.IntegrationTest;

@IntegrationTest
class LedgerApplicationIT {

	@Autowired
	JdbcTemplate jdbc;

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

	// Seeding assertions (FX liquidity funded once, restart-safe) arrive with the seeder in P4.
}
