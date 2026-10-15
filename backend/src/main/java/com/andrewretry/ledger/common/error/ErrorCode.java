package com.andrewretry.ledger.common.error;

import java.util.Locale;

import org.springframework.http.HttpStatus;

/** Every error the API can return: its HTTP status and a default title. See PLAN.md §8.3. */
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request is not valid"),
	IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required"),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Admin key missing or wrong"),

	ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Account not found"),
	TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transaction not found"),
	RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "Run not found"),

	ALREADY_REVERSED(HttpStatus.CONFLICT, "Transaction already reversed"),
	EXTERNAL_REF_ALREADY_USED(HttpStatus.CONFLICT, "External reference already used"),

	INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds"),
	INSUFFICIENT_LIQUIDITY(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient FX liquidity"),
	SAME_ACCOUNT(HttpStatus.UNPROCESSABLE_CONTENT, "Source and destination are the same account"),
	ACCOUNT_KIND_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "Account kind not allowed for this operation"),
	CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "Currency does not match the account"),
	CURRENCY_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "Currency not supported"),
	/** A safety check: every pair of the supported currencies has a seeded rate, and rates are never deleted. */
	FX_RATE_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "No exchange rate for this currency pair"),
	FX_AMOUNT_TOO_SMALL(HttpStatus.UNPROCESSABLE_CONTENT, "Amount is too small to convert"),
	IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key was used with a different request"),
	CANNOT_REVERSE_REVERSAL(HttpStatus.UNPROCESSABLE_CONTENT, "A reversal cannot be reversed"),

	INVARIANT_VIOLATION(HttpStatus.INTERNAL_SERVER_ERROR, "Ledger invariant violated"),

	LEDGER_INTEGRITY_HOLD(HttpStatus.SERVICE_UNAVAILABLE, "Writes are on hold after an integrity failure"),
	TRY_AGAIN(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily busy, retry with the same Idempotency-Key");

	private static final String TYPE_PREFIX = "https://ledger.example/errors/";

	private final HttpStatus status;
	private final String title;

	ErrorCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}

	/** Problem type URI, e.g. {@code https://ledger.example/errors/account-not-found}. */
	public String type() {
		return TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
