package com.andrewretry.ledger.account;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, UUID> {

	/** Pre-lock lookup: never creates a managed Account entity. */
	@Query("select new com.andrewretry.ledger.account.AccountCurrencyView(a.id, a.currency, a.kind) "
			+ "from Account a where a.id = :id")
	Optional<AccountCurrencyView> findViewById(@Param("id") UUID id);

	/** The FUNDING or FX_LIQUIDITY account of a currency (a unique index guarantees exactly one). */
	@Query("select new com.andrewretry.ledger.account.AccountCurrencyView(a.id, a.currency, a.kind) "
			+ "from Account a where a.kind = :kind and a.currency = :currency")
	AccountCurrencyView findSystemAccount(@Param("kind") AccountKind kind, @Param("currency") String currency);

	/** The only way a command loads accounts: all of them in one query, locked, in id order (no deadlocks). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Account a where a.id in :ids order by a.id")
	List<Account> lockAllByIdOrdered(@Param("ids") Collection<UUID> ids);

}
