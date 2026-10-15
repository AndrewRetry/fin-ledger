package com.andrewretry.ledger.account;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.andrewretry.ledger.account.dto.AccountResponse;
import com.andrewretry.ledger.common.Currencies;
import com.andrewretry.ledger.common.DbClock;
import com.andrewretry.ledger.common.PageResponse;
import com.andrewretry.ledger.common.error.LedgerException;

@Service
public class AccountService {

	private final AccountRepository accounts;
	private final Currencies currencies;
	private final DbClock dbClock;

	public AccountService(AccountRepository accounts, Currencies currencies, DbClock dbClock) {
		this.accounts = accounts;
		this.currencies = currencies;
		this.dbClock = dbClock;
	}

	@Transactional
	public AccountResponse create(String name, String currency) {
		if (!currencies.isSupported(currency)) {
			throw LedgerException.currencyNotSupported(currency);
		}
		Account account = accounts.save(new Account(name.trim(), currency, dbClock.now()));
		return AccountResponse.of(account, currencies);
	}

	/** Creates a customer account with a fixed id unless it already exists (startup demo data). */
	@Transactional
	public void createIfMissing(UUID id, String name, String currency) {
		if (!accounts.existsById(id)) {
			accounts.save(new Account(id, name, currency, dbClock.now()));
		}
	}

	@Transactional(readOnly = true)
	public AccountResponse get(UUID id) {
		Account account = accounts.findById(id).orElseThrow(() -> LedgerException.accountNotFound(id));
		return AccountResponse.of(account, currencies);
	}

	/** Oldest first; the id breaks ties so paging is stable. */
	@Transactional(readOnly = true)
	public PageResponse<AccountResponse> list(int page, int size) {
		PageRequest request = PageRequest.of(page, size, Sort.by("createdAt", "id"));
		return PageResponse.of(accounts.findAll(request), account -> AccountResponse.of(account, currencies));
	}

}
