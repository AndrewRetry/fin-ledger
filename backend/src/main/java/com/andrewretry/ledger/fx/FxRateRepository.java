package com.andrewretry.ledger.fx;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FxRateRepository extends JpaRepository<FxRate, UUID> {

	/** The rate in use for a pair: the newest row (ties broken by id, so the answer is always the same). */
	Optional<FxRate> findFirstByBaseAndQuoteOrderByCreatedAtDescIdAsc(String base, String quote);

	/** The rate in use for every pair, ordered by base, then quote. */
	@Query(value = """
			select distinct on (base, quote) *
			  from fx_rates
			 order by base, quote, created_at desc, id""", nativeQuery = true)
	List<FxRate> findLatestPerPair();

}
