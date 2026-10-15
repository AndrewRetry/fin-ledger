package com.andrewretry.ledger.integrity;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.andrewretry.ledger.integrity.dto.HoldStatus;

/**
 * /actuator/health reports DOWN with the reason while the hold is on, so monitoring notices. The liveness probe
 * does not include it: a held ledger is alive and still serves reads, so it must not be restarted.
 */
@Component("integrity")
public class IntegrityHealthIndicator implements HealthIndicator {

	private final IntegrityHoldService holds;

	public IntegrityHealthIndicator(IntegrityHoldService holds) {
		this.holds = holds;
	}

	@Override
	public Health health() {
		HoldStatus status = holds.status();
		return status.held()
				? Health.down().withDetail("reason", status.reason()).withDetail("since", status.since()).build()
				: Health.up().build();
	}

}
