package com.andrewretry.ledger.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Used only to decide "has this month ended?". Anything stored in the ledger takes its time from DbClock;
 * tests may replace this bean.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
