package com.andrewretry.ledger.common;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * Canonical byte encoding used for hashes: each field is a 4-byte big-endian length followed by its UTF-8 bytes,
 * so no two different field lists can produce the same bytes.
 */
public final class Canonical {

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	public Canonical field(String s) {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		out.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
		out.writeBytes(b);
		return this;
	}

	public Canonical field(long n) {
		return field(Long.toString(n));
	}

	public byte[] bytes() {
		return out.toByteArray();
	}

	/** Lowercase hex SHA-256 of the bytes so far. */
	public String sha256Hex() {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes()));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

	/** Epoch microseconds, the timestamp form that is hashed. */
	public static long micros(Instant t) {
		return ChronoUnit.MICROS.between(Instant.EPOCH, t);
	}

}
