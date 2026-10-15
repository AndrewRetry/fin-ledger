package com.andrewretry.ledger.account.dto;

import java.time.Instant;
import java.util.UUID;

import com.andrewretry.ledger.account.Account;
import com.andrewretry.ledger.account.AccountKind;
import com.andrewretry.ledger.common.Currencies;
import com.andrewretry.ledger.common.Money;

public record AccountResponse(UUID id, String name, AccountKind kind, String currency, Money balance, long version,
		Instant createdAt) {

	public static AccountResponse of(Account account, Currencies currencies) {
		return new AccountResponse(account.getId(), account.getName(), account.getKind(), account.getCurrency(),
				Money.of(account.getBalance(), account.getCurrency(), currencies), account.getVersion(),
				account.getCreatedAt());
	}

}
