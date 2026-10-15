package com.andrewretry.ledger.account;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.andrewretry.ledger.account.dto.AccountResponse;
import com.andrewretry.ledger.account.dto.CreateAccountRequest;
import com.andrewretry.ledger.common.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
public class AccountController {

	private final AccountService accounts;

	public AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	@PostMapping("/accounts")
	ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
		AccountResponse created = accounts.create(request.name(), request.currency());
		return ResponseEntity.created(URI.create("/accounts/" + created.id())).body(created);
	}

	@GetMapping("/accounts")
	PageResponse<AccountResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
		return accounts.list(page, size);
	}

	@GetMapping("/accounts/{id}")
	AccountResponse get(@PathVariable UUID id) {
		return accounts.get(id);
	}

}
