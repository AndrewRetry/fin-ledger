package com.andrewretry.ledger.common.error;

import java.util.Map;
import java.util.UUID;

/** A business-rule failure with an {@link ErrorCode}; {@link GlobalExceptionHandler} turns it into a problem response. */
public class LedgerException extends RuntimeException {

	private final ErrorCode code;
	private final Map<String, Object> properties;

	public LedgerException(ErrorCode code, String detail) {
		this(code, detail, Map.of());
	}

	public LedgerException(ErrorCode code, String detail, Map<String, Object> properties) {
		super(detail);
		this.code = code;
		this.properties = Map.copyOf(properties);
	}

	public ErrorCode code() {
		return code;
	}

	public Map<String, Object> properties() {
		return properties;
	}

	public static LedgerException accountNotFound(UUID accountId) {
		return new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "No account with id " + accountId + ".",
				Map.of("accountId", accountId));
	}

	public static LedgerException currencyNotSupported(String currency) {
		return new LedgerException(ErrorCode.CURRENCY_NOT_SUPPORTED,
				"Currency '" + currency + "' is not supported. Use an ISO 4217 code such as SGD, USD, EUR or JPY.");
	}

	public static LedgerException insufficientFunds(UUID accountId) {
		return new LedgerException(ErrorCode.INSUFFICIENT_FUNDS,
				"Account " + accountId + " does not hold enough money for this amount.", Map.of("accountId", accountId));
	}

	public static LedgerException insufficientLiquidity(UUID accountId) {
		return new LedgerException(ErrorCode.INSUFFICIENT_LIQUIDITY,
				"House FX liquidity account " + accountId + " cannot cover this amount; try a smaller amount later.",
				Map.of("accountId", accountId));
	}

}
