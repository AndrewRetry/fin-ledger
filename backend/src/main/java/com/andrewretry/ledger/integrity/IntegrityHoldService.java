package com.andrewretry.ledger.integrity;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.andrewretry.ledger.common.DbClock;
import com.andrewretry.ledger.integrity.dto.HoldStatus;
import com.andrewretry.ledger.journal.LedgerState;
import com.andrewretry.ledger.journal.LedgerStateRepository;

/**
 * The fail-closed switch on the ledger_state row. While it is on, commands stop at step 5 with 503; reads go on.
 * Only the chain head is locked here, never an account, so the global lock order is kept.
 */
@Service
public class IntegrityHoldService {

	private static final Logger log = LoggerFactory.getLogger(IntegrityHoldService.class);
	private static final int MAX_REASON = 500;

	private final LedgerStateRepository ledgerState;
	private final DbClock dbClock;
	private final JdbcTemplate jdbc;

	public IntegrityHoldService(LedgerStateRepository ledgerState, DbClock dbClock, JdbcTemplate jdbc) {
		this.ledgerState = ledgerState;
		this.dbClock = dbClock;
		this.jdbc = jdbc;
	}

	/** Turns the hold on. Already on: keeps the first reason and time. */
	@Transactional
	public void hold(String reason) {
		LedgerState head = ledgerState.lockHead();
		if (!head.isIntegrityHold()) {
			head.hold(reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason, dbClock.now());
			log.error("Integrity hold ON, writes stopped: {}", reason);
		}
	}

	/** If the data is still broken, the next integrity run turns the hold back on. */
	@Transactional
	public HoldStatus release(String reason) {
		ledgerState.lockHead().release();
		log.warn("Integrity hold released: {}", reason);
		return new HoldStatus(false, null, null);
	}

	/** A plain read, cheap enough for the header filter to call on every request. */
	public HoldStatus status() {
		return jdbc.queryForObject("select integrity_hold, hold_reason, hold_since from ledger_state where id = 1",
				(rs, row) -> {
					OffsetDateTime since = rs.getObject("hold_since", OffsetDateTime.class);
					return new HoldStatus(rs.getBoolean("integrity_hold"), rs.getString("hold_reason"),
							since == null ? null : since.toInstant());
				});
	}

}
