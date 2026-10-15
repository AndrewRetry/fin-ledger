package com.andrewretry.ledger.integrity;

import java.time.Clock;
import java.time.YearMonth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.andrewretry.ledger.config.LedgerProperties;

/** The integrity checks every ledger.integrity.interval, and the previous month's close on ledger.close.cron. */
@Component
@ConditionalOnProperty(name = "ledger.jobs.enabled", havingValue = "true")
public class IntegrityJobs {

	private static final Logger log = LoggerFactory.getLogger(IntegrityJobs.class);

	private final IntegrityService integrity;
	private final MonthEndService monthEnd;
	private final Clock clock;
	private final LedgerProperties properties;

	public IntegrityJobs(IntegrityService integrity, MonthEndService monthEnd, Clock clock, LedgerProperties properties) {
		this.integrity = integrity;
		this.monthEnd = monthEnd;
		this.clock = clock;
		this.properties = properties;
	}

	@Scheduled(initialDelayString = "${ledger.integrity.interval}", fixedDelayString = "${ledger.integrity.interval}")
	public void runIntegrityChecks() {
		log.info("Scheduled integrity run: {}", integrity.run().status());
	}

	@Scheduled(cron = "${ledger.close.cron}", zone = "${ledger.close.zone}")
	public void closePreviousMonth() {
		YearMonth previous = YearMonth.now(clock.withZone(properties.close().zone())).minusMonths(1);
		log.info("Scheduled month-end close of {}: {}", previous, monthEnd.close(previous).status());
	}

}
