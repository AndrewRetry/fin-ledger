package com.andrewretry.ledger.journal;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import jakarta.persistence.LockModeType;

public interface LedgerStateRepository extends Repository<LedgerState, Short> {

	/** Always the LAST lock a command takes, after all its accounts. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from LedgerState s where s.id = 1")
	LedgerState lockHead();

	/** Plain read, for GET /ledger/head. */
	@Query("select s from LedgerState s where s.id = 1")
	LedgerState head();

}
