package com.andrewretry.ledger.account;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

	/** Pre-lock lookup: never creates managed Account entities. */
	@Query("select new com.andrewretry.ledger.account.AccountCurrencyView(a.id, a.currency, a.kind) "
			+ "from Account a where a.id in :ids")
	List<AccountCurrencyView> findViewsByIdIn(@Param("ids") Collection<UUID> ids);

}
