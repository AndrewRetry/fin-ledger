package com.andrewretry.ledger.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The database's clock. Anything stored or hashed takes its time from here, never from the JVM, so timestamps
 * and chain order agree. JdbcTemplate joins the current JPA transaction (same connection).
 */
@Component
public class DbClock {

	private final JdbcTemplate jdbc;

	public DbClock(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Instant now() {
		OffsetDateTime now = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
		return now.toInstant().truncatedTo(ChronoUnit.MICROS);
	}

}
