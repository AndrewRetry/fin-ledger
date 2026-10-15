package com.andrewretry.ledger.common.error;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.andrewretry.ledger.common.ConstraintNames;
import com.andrewretry.ledger.web.RequestIdFilter;

/**
 * Every error leaves the API as RFC 9457 problem+json with a stable {@code code} and the {@code requestId}.
 * Never a stack trace; the detail tells the caller what to do.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(LedgerException.class)
	ResponseEntity<Object> handleLedger(LedgerException e, WebRequest request) {
		ErrorCode code = e.code();
		ProblemDetail problem = problem(code, e.getMessage(), request);
		e.properties().forEach(problem::setProperty);
		if (code == ErrorCode.INVARIANT_VIOLATION) {
			log.error("{} {}", code, e.getMessage(), e);
		}
		else {
			log.warn("Rejected request: {}", code);
		}
		return ResponseEntity.status(code.status()).body(problem);
	}

	/**
	 * A unique constraint fired at flush or commit. Three are expected races between requests that share no lock
	 * (PLAN.md §7.1); any other constraint or trigger firing means the Java code let something wrong through.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException e, WebRequest request) {
		if (ConstraintNames.is(e, "uq_jt_idempotency_key")) {
			return handleLedger(new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
					"This Idempotency-Key was used at the same moment for a different request. Use a new key."), request);
		}
		if (ConstraintNames.is(e, "uq_jt_reverses_id")) {
			return handleLedger(new LedgerException(ErrorCode.ALREADY_REVERSED,
					"This transaction has already been reversed."), request);
		}
		if (ConstraintNames.is(e, "uq_jt_external_ref")) {
			return handleLedger(new LedgerException(ErrorCode.EXTERNAL_REF_ALREADY_USED,
					"This external reference is already booked; the same outside movement cannot be booked twice."),
					request);
		}
		log.error("Database guardrail rejected a write (constraint '{}')", ConstraintNames.of(e), e);
		return internalError(request);
	}

	/**
	 * Lock timeout, deadlock, a transaction over its time limit, or no database connection available:
	 * nothing was written, so retrying is safe.
	 */
	@ExceptionHandler({ TransientDataAccessException.class, TransactionTimedOutException.class,
			CannotCreateTransactionException.class })
	ResponseEntity<Object> handleTransient(Exception e, WebRequest request) {
		log.warn("Rejected request: {} ({})", ErrorCode.TRY_AGAIN, e.getClass().getSimpleName());
		ProblemDetail problem = problem(ErrorCode.TRY_AGAIN,
				"The ledger is busy. Retry in a moment with the same Idempotency-Key; it is safe.", request);
		return ResponseEntity.status(ErrorCode.TRY_AGAIN.status()).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
	}

	/** Overflow from {@code Math.addExact} and friends is a bug, never a wrap-around. */
	@ExceptionHandler(ArithmeticException.class)
	ResponseEntity<Object> handleArithmetic(ArithmeticException e, WebRequest request) {
		log.error("Arithmetic overflow", e);
		return internalError(request);
	}

	/** Last resort: nothing unexpected leaves the API without a problem body or a log line. */
	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception e, WebRequest request) {
		log.error("Unexpected error", e);
		return internalError(request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
				.map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
				.toList();
		return validationFailed("One or more fields are invalid. See 'errors'.", errors, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = e.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream().map(error -> Map.of(
						"field", String.valueOf(result.getMethodParameter().getParameterName()),
						"message", String.valueOf(error.getDefaultMessage()))))
				.toList();
		return validationFailed("One or more parameters are invalid. See 'errors'.", errors, request);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return validationFailed("The request body is missing, is not valid JSON, has an unknown field, or a field has the "
				+ "wrong type. Send amounts as integer minor units, e.g. 2500 for 25.00.", List.of(), request);
	}

	/** Every other framework error (bad path variable, 404, 405, ...): same body shape, code from the status. */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = body instanceof ProblemDetail existing ? existing : ProblemDetail.forStatus(status);
		String code = status.value() == HttpStatus.BAD_REQUEST.value() ? ErrorCode.VALIDATION_FAILED.name()
				: HttpStatus.valueOf(status.value()).name();
		decorate(problem, code, request);
		return super.handleExceptionInternal(e, problem, headers, status, request);
	}

	private ResponseEntity<Object> validationFailed(String detail, List<Map<String, String>> errors, WebRequest request) {
		ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, detail, request);
		if (!errors.isEmpty()) {
			problem.setProperty("errors", errors);
		}
		return ResponseEntity.badRequest().body(problem);
	}

	private ResponseEntity<Object> internalError(WebRequest request) {
		ProblemDetail problem = problem(ErrorCode.INVARIANT_VIOLATION,
				"The ledger hit an internal error. Quote the requestId when reporting it.", request);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
	}

	private static ProblemDetail problem(ErrorCode code, String detail, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
		problem.setTitle(code.title());
		problem.setType(URI.create(code.type()));
		decorate(problem, code.name(), request);
		return problem;
	}

	private static void decorate(ProblemDetail problem, String code, WebRequest request) {
		problem.setProperty("code", code);
		problem.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
		if (request instanceof ServletWebRequest servlet) {
			problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
		}
	}

}
