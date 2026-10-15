package com.andrewretry.ledger.support;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Pretends it is the 5th of next month (Singapore time), so the current month has ended and can be closed for real.
 * Only "has this month ended?" uses this clock; stored timestamps still come from the database, i.e. this month.
 * Importing it gives the test class its own Spring context, and so its own database.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfig {

	public static final ZoneId ZONE = ZoneId.of("Asia/Singapore");

	/** The month the database clock is in: every transaction a test writes lands here. */
	public static YearMonth thisMonth() {
		return YearMonth.now(ZONE);
	}

	@Bean
	@Primary
	Clock fifthOfNextMonth() {
		return Clock.fixed(thisMonth().plusMonths(1).atDay(5).atStartOfDay(ZONE).toInstant(), ZONE);
	}

}
