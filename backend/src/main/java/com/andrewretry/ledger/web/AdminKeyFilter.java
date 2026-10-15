package com.andrewretry.ledger.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.UrlPathHelper;

import com.andrewretry.ledger.common.error.ErrorCode;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.config.LedgerProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Everything under /admin needs {@code X-Admin-Key} equal to {@code ledger.admin-key}. A blank configured key
 * locks /admin entirely. A filter runs outside Spring MVC, so the 401 is handed to the MVC exception resolver,
 * which makes GlobalExceptionHandler write the same problem body as every other error.
 */
@Component
public class AdminKeyFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Admin-Key";

	private final byte[] adminKey;
	private final HandlerExceptionResolver resolver;

	public AdminKeyFilter(LedgerProperties properties,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
		String configured = properties.adminKey();
		this.adminKey = configured == null || configured.isBlank() ? null : configured.getBytes(StandardCharsets.UTF_8);
		this.resolver = resolver;
	}

	/**
	 * Uses the decoded path without ;parameters, which is what Spring MVC routes on. The raw URI would let
	 * /admin;x=1/fx-rates or /%61dmin/fx-rates reach an admin endpoint without passing this check.
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !UrlPathHelper.defaultInstance.getLookupPathForRequest(request).startsWith("/admin/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (isAuthorized(request.getHeader(HEADER))) {
			chain.doFilter(request, response);
			return;
		}
		resolver.resolveException(request, response, null,
				new LedgerException(ErrorCode.UNAUTHORIZED, "Send the admin key in the X-Admin-Key header."));
	}

	/** Constant-time comparison, so response timing reveals nothing about the key. */
	private boolean isAuthorized(String sent) {
		return adminKey != null && sent != null
				&& MessageDigest.isEqual(adminKey, sent.getBytes(StandardCharsets.UTF_8));
	}

}
