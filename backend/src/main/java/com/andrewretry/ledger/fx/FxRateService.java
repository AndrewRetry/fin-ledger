package com.andrewretry.ledger.fx;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.andrewretry.ledger.common.Currencies;
import com.andrewretry.ledger.common.DbClock;
import com.andrewretry.ledger.common.error.ErrorCode;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.fx.dto.FxRateResponse;
import com.andrewretry.ledger.journal.JournalTransaction;

@Service
public class FxRateService {

	private final FxRateRepository rates;
	private final Currencies currencies;
	private final DbClock dbClock;

	public FxRateService(FxRateRepository rates, Currencies currencies, DbClock dbClock) {
		this.rates = rates;
		this.currencies = currencies;
		this.dbClock = dbClock;
	}

	/** The rate to use now for base to quote. Joins the caller's transaction. */
	@Transactional(readOnly = true)
	public FxRate latest(String base, String quote) {
		return rates.findFirstByBaseAndQuoteOrderByCreatedAtDescIdAsc(base, quote)
				.orElseThrow(() -> new LedgerException(ErrorCode.FX_RATE_UNAVAILABLE,
						"There is no exchange rate from " + base + " to " + quote + "."));
	}

	/** The exact rate an FX transfer used, or null for every other transaction. Joins the caller's transaction. */
	@Transactional(readOnly = true)
	public FxRate usedBy(JournalTransaction tx) {
		return tx.getFxRateId() == null ? null : rates.findById(tx.getFxRateId()).orElseThrow();
	}

	@Transactional(readOnly = true)
	public List<FxRateResponse> latestPerPair() {
		return rates.findLatestPerPair().stream().map(FxRateResponse::of).toList();
	}

	/** A new row, never an update: transactions keep pointing at the exact rate they used. */
	@Transactional
	public FxRateResponse add(String base, String quote, String rate) {
		requireSupported(base);
		requireSupported(quote);
		if (base.equals(quote)) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, "base and quote must be different currencies.");
		}
		BigDecimal value = new BigDecimal(rate);
		if (value.signum() <= 0) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, "rate must be greater than 0.");
		}
		return FxRateResponse.of(rates.save(FxRate.manual(base, quote, value, dbClock.now())));
	}

	private void requireSupported(String currency) {
		if (!currencies.isSupported(currency)) {
			throw LedgerException.currencyNotSupported(currency);
		}
	}

}
