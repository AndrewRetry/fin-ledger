package com.andrewretry.ledger.integrity;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.andrewretry.ledger.common.error.ErrorCode;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.integrity.dto.IntegrityReport;

@RestController
public class ReconciliationController {

	private final MonthEndService monthEnd;

	public ReconciliationController(MonthEndService monthEnd) {
		this.monthEnd = monthEnd;
	}

	/** {@code period} is a month, e.g. 2026-10. Also runs on a schedule for the previous month. */
	@PostMapping("/reconciliations/month-end")
	IntegrityReport closeMonth(@RequestParam String period) {
		return monthEnd.close(parse(period));
	}

	private static YearMonth parse(String period) {
		try {
			return YearMonth.parse(period);
		}
		catch (DateTimeParseException e) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, "period must be a month like 2026-10.");
		}
	}

}
