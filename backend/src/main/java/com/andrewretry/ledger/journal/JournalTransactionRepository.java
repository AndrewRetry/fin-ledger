package com.andrewretry.ledger.journal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JournalTransactionRepository extends JpaRepository<JournalTransaction, UUID> {

	Optional<JournalTransaction> findByIdempotencyKey(String idempotencyKey);

	boolean existsByExternalRef(String externalRef);

	boolean existsByReversesId(UUID reversesId);

	@Query("select t.id from JournalTransaction t where t.reversesId = :id")
	Optional<UUID> findReversalIdOf(UUID id);

}
