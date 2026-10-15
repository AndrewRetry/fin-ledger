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

	public static LedgerException transactionNotFound(UUID transactionId) {
		return new LedgerException(ErrorCode.TRANSACTION_NOT_FOUND, "No transaction with id " + transactionId + ".",
				Map.of("transactionId", transactionId));
	}

	public static LedgerException sameAccount() {
		return new LedgerException(ErrorCode.SAME_ACCOUNT, "Source and destination must be different accounts.");
	}

	public static LedgerException kindNotAllowed(UUID accountId, String allowed) {
		return new LedgerException(ErrorCode.ACCOUNT_KIND_NOT_ALLOWED,
				"Account " + accountId + " cannot be used here; only " + allowed + " accounts can.", Map.of("accountId", accountId));
	}

	public static LedgerException currencyMismatch(String sent, String accountCurrency) {
		return new LedgerException(ErrorCode.CURRENCY_MISMATCH, "The amount is in " + sent + " but the source account holds "
				+ accountCurrency + ". Send currency " + accountCurrency + " or leave it out.");
	}

	public static LedgerException idempotencyKeyReused(String key) {
		return new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key '" + key
				+ "' was already used for a different request. Use a new key for a new request.");
	}

	public static LedgerException externalRefAlreadyUsed(String externalRef) {
		return new LedgerException(ErrorCode.EXTERNAL_REF_ALREADY_USED, "External reference '" + externalRef
				+ "' is already booked; the same outside movement cannot be booked twice.");
	}

	public static LedgerException integrityHold() {
		return new LedgerException(ErrorCode.LEDGER_INTEGRITY_HOLD,
				"Writes are paused until an administrator reviews a failed integrity check. Reads still work.");
	}

}
