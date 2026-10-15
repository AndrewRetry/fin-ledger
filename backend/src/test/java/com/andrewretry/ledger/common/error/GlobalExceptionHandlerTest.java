package com.andrewretry.ledger.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

/** The races of PLAN.md §7.1 are hard to stage over HTTP, so their mapping is checked here directly. */
class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
	private final WebRequest request = new ServletWebRequest(new MockHttpServletRequest("POST", "/transactions"));

	@Test
	void mapsAnIdempotencyKeyRaceTo422() {
		assertThat(codeOf(handler.handleDataIntegrity(violation("uq_jt_idempotency_key"), request)))
				.isEqualTo("422 IDEMPOTENCY_KEY_REUSED");
	}

	@Test
	void mapsAReversalRaceTo409() {
		assertThat(codeOf(handler.handleDataIntegrity(violation("uq_jt_reverses_id"), request)))
				.isEqualTo("409 ALREADY_REVERSED");
	}

	@Test
	void mapsAnExternalReferenceRaceTo409() {
		assertThat(codeOf(handler.handleDataIntegrity(violation("uq_jt_external_ref"), request)))
				.isEqualTo("409 EXTERNAL_REF_ALREADY_USED");
	}

	@Test
	void mapsAnyOtherGuardrailTo500() {
		assertThat(codeOf(handler.handleDataIntegrity(violation("ck_accounts_non_negative"), request)))
				.isEqualTo("500 INVARIANT_VIOLATION");
	}

	@Test
	void mapsLockTimeoutsTo503WithRetryAfter() {
		ResponseEntity<Object> response = handler.handleTransient(new CannotAcquireLockException("lock timeout"), request);

		assertThat(codeOf(response)).isEqualTo("503 TRY_AGAIN");
		assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
	}

	@Test
	void mapsATransactionTimeoutTo503() {
		assertThat(codeOf(handler.handleTransient(new TransactionTimedOutException("too slow"), request)))
				.isEqualTo("503 TRY_AGAIN");
	}

	@Test
	void mapsAnUnreachableDatabaseTo503() {
		assertThat(codeOf(handler.handleTransient(new CannotCreateTransactionException("no connection"), request)))
				.isEqualTo("503 TRY_AGAIN");
	}

	/** What PostgreSQL sends for a violated constraint: SQLSTATE and the constraint name. */
	private static DataIntegrityViolationException violation(String constraint) {
		PSQLException psql = new PSQLException(new ServerErrorMessage("SERROR\0C23505\0Mduplicate key\0n" + constraint + "\0"));
		return new DataIntegrityViolationException("could not execute statement", psql);
	}

	private static String codeOf(ResponseEntity<Object> response) {
		ProblemDetail problem = (ProblemDetail) response.getBody();
		return response.getStatusCode().value() + " " + problem.getProperties().get("code");
	}

}
