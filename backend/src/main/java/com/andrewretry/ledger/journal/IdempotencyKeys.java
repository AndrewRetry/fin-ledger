package com.andrewretry.ledger.journal;

import java.util.regex.Pattern;

import com.andrewretry.ledger.common.error.ErrorCode;
import com.andrewretry.ledger.common.error.LedgerException;

/** Reads the Idempotency-Key header. Some clients quote header values, so one pair of quotes is removed. */
public final class IdempotencyKeys {

	private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,254}");

	private IdempotencyKeys() {
	}

	public static String parse(String header) {
		if (header == null || header.isBlank()) {
			throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_MISSING,
					"Send an Idempotency-Key header with a unique value per request, e.g. a UUID.");
		}
		String key = header.length() >= 2 && header.startsWith("\"") && header.endsWith("\"")
				? header.substring(1, header.length() - 1)
				: header;
		if (!FORMAT.matcher(key).matches()) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED,
					"Idempotency-Key must be 1-255 characters of letters, digits and . _ : - and start with a letter or digit.");
		}
		return key;
	}

}
