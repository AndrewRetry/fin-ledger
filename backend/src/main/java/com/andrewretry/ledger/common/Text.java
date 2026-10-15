package com.andrewretry.ledger.common;

/** The one normalisation rule for optional text in money-moving requests (PLAN.md §7.1). */
public final class Text {

	private Text() {
	}

	/** Trimmed; blank means absent (null). */
	public static String clean(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

}
