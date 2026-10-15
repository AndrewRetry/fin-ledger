package com.andrewretry.ledger.common;

import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** Finds the name of the database constraint behind an exception, or "" when there is none. */
public final class ConstraintNames {

	private ConstraintNames() {
	}

	public static String of(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof ConstraintViolationException hibernate && hibernate.getConstraintName() != null) {
				return hibernate.getConstraintName();
			}
		}
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof PSQLException psql) {
				ServerErrorMessage server = psql.getServerErrorMessage();
				if (server != null && server.getConstraint() != null) {
					return server.getConstraint();
				}
			}
		}
		return "";
	}

	/** Case-insensitive; Hibernate may report the name with a schema prefix, hence endsWith. */
	public static boolean is(Throwable error, String constraint) {
		return of(error).toLowerCase().endsWith(constraint.toLowerCase());
	}

}
