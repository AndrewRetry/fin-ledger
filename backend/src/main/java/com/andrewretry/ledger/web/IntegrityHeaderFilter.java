package com.andrewretry.ledger.web;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.andrewretry.ledger.integrity.IntegrityHoldService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** While the integrity hold is on, every response says so, reads included: the numbers are under suspicion. */
@Component
public class IntegrityHeaderFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Ledger-Integrity";

	private final IntegrityHoldService holds;

	public IntegrityHeaderFilter(IntegrityHoldService holds) {
		this.holds = holds;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (holds.status().held()) {
			response.setHeader(HEADER, "HOLD");
		}
		chain.doFilter(request, response);
	}

}
