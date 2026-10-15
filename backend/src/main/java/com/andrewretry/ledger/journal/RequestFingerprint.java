package com.andrewretry.ledger.journal;

import java.util.Objects;
import java.util.UUID;

import com.andrewretry.ledger.common.Canonical;

/**
 * Fingerprint of a money-moving request, stored next to its idempotency key: same key + same fingerprint is a
 * replay, same key + different fingerprint is 422. Callers pass already-normalised values (trimmed, blank = null).
 */
public final class RequestFingerprint {

	private RequestFingerprint() {
	}

	public static String transfer(UUID source, UUID destination, long amount, String currency, String description) {
		return of("TRANSFER-V1", source, destination, amount, currency, description);
	}

	public static String deposit(UUID account, long amount, String description, String externalRef) {
		return of("DEPOSIT-V1", account, amount, description, externalRef);
	}

	public static String withdrawal(UUID account, long amount, String description, String externalRef) {
		return of("WITHDRAWAL-V1", account, amount, description, externalRef);
	}

	public static String reversal(UUID transactionId, String reason) {
		return of("REVERSAL-V1", transactionId, reason);
	}

	private static String of(Object... fields) {
		Canonical c = new Canonical();
		for (Object field : fields) {
			c.field(Objects.toString(field, ""));
		}
		return c.sha256Hex();
	}

}
