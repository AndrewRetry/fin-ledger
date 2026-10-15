package com.andrewretry.ledger.journal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.andrewretry.ledger.account.Account;
import com.andrewretry.ledger.account.AccountCurrencyView;
import com.andrewretry.ledger.account.AccountKind;
import com.andrewretry.ledger.account.AccountRepository;
import com.andrewretry.ledger.common.Currencies;
import com.andrewretry.ledger.common.DbClock;
import com.andrewretry.ledger.common.error.ErrorCode;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.journal.dto.TransactionView;

import jakarta.persistence.EntityManager;

/**
 * Every command that moves money. Each public method is one database transaction and follows the same numbered
 * steps (PLAN.md §7.1); the order of the steps is part of the contract, see the comments in {@link #post}.
 */
@Service
public class LedgerCommandService {

	private static final Logger log = LoggerFactory.getLogger(LedgerCommandService.class);

	private final AccountRepository accounts;
	private final JournalTransactionRepository journal;
	private final LedgerStateRepository ledgerState;
	private final DbClock dbClock;
	private final Currencies currencies;
	private final EntityManager entityManager;

	public LedgerCommandService(AccountRepository accounts, JournalTransactionRepository journal,
			LedgerStateRepository ledgerState, DbClock dbClock, Currencies currencies, EntityManager entityManager) {
		this.accounts = accounts;
		this.journal = journal;
		this.ledgerState = ledgerState;
		this.dbClock = dbClock;
		this.currencies = currencies;
		this.entityManager = entityManager;
	}

	/** One posting to be applied: which account, which way, how much. */
	private record Leg(UUID accountId, Direction direction, long amount) {
	}

	/** The outcome of step 1: everything needed to post, decided before any lock is taken. */
	private record Planned(TransactionType type, String idempotencyKey, String requestHash, String externalRef,
			String description, List<Leg> legs) {

		Set<UUID> accountIds() {
			return legs.stream().map(Leg::accountId).collect(Collectors.toSet());
		}
	}

	@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
	public CommandResult deposit(String idempotencyKey, UUID accountId, long amount, String description,
			String externalRef) {
		description = clean(description);
		externalRef = clean(externalRef);
		// 1. Resolve and validate (no locks): money comes in from the outside world of the wallet's currency.
		AccountCurrencyView wallet = walletForMovement(accountId);
		AccountCurrencyView funding = accounts.findSystemAccount(AccountKind.FUNDING, wallet.currency());
		return post(new Planned(TransactionType.DEPOSIT, idempotencyKey,
				RequestFingerprint.deposit(accountId, amount, description, externalRef), externalRef, description,
				List.of(new Leg(funding.id(), Direction.DEBIT, amount), new Leg(wallet.id(), Direction.CREDIT, amount))));
	}

	@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
	public CommandResult withdraw(String idempotencyKey, UUID accountId, long amount, String description,
			String externalRef) {
		description = clean(description);
		externalRef = clean(externalRef);
		// 1. Resolve and validate (no locks): money goes back to the outside world of the wallet's currency.
		AccountCurrencyView wallet = walletForMovement(accountId);
		AccountCurrencyView funding = accounts.findSystemAccount(AccountKind.FUNDING, wallet.currency());
		return post(new Planned(TransactionType.WITHDRAWAL, idempotencyKey,
				RequestFingerprint.withdrawal(accountId, amount, description, externalRef), externalRef, description,
				List.of(new Leg(wallet.id(), Direction.DEBIT, amount), new Leg(funding.id(), Direction.CREDIT, amount))));
	}

	@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
	public CommandResult transfer(String idempotencyKey, UUID sourceId, UUID destinationId, long amount,
			String currency, String description) {
		currency = clean(currency);
		description = clean(description);
		// 1. Resolve and validate (no locks), in the order of PLAN.md §7.2.
		AccountCurrencyView source = find(sourceId);
		AccountCurrencyView destination = find(destinationId);
		if (sourceId.equals(destinationId)) {
			throw LedgerException.sameAccount();
		}
		requireCustomer(source);
		requireCustomer(destination);
		if (currency != null && !currency.equals(source.currency())) {
			throw LedgerException.currencyMismatch(currency, source.currency());
		}
		if (!source.currency().equals(destination.currency())) {
			// Cross-currency transfers need the FX path (PLAN.md §7.3, phase P4); refused until it exists.
			throw new LedgerException(ErrorCode.FX_RATE_UNAVAILABLE, "Transfers between " + source.currency()
					+ " and " + destination.currency() + " accounts are not enabled.");
		}
		return post(new Planned(TransactionType.TRANSFER, idempotencyKey,
				RequestFingerprint.transfer(sourceId, destinationId, amount, currency, description), null, description,
				List.of(new Leg(sourceId, Direction.DEBIT, amount), new Leg(destinationId, Direction.CREDIT, amount))));
	}

	/** Steps 2-7, shared by every command. Runs inside the caller's transaction. */
	private CommandResult post(Planned planned) {
		// 2. Lock every account the command touches: one query, ascending id. Same order for everyone = no deadlock.
		Map<UUID, Account> locked = accounts.lockAllByIdOrdered(planned.accountIds()).stream()
				.collect(Collectors.toMap(Account::getId, Function.identity()));

		// 3. Idempotency, BEFORE any business check: a retry of a request that already succeeded must be replayed,
		//    not re-judged. A duplicate in flight waited at step 2 for the first one to commit, so it finds it here.
		Optional<JournalTransaction> earlier = journal.findByIdempotencyKey(planned.idempotencyKey());
		if (earlier.isPresent()) {
			if (!earlier.get().getRequestHash().equals(planned.requestHash())) {
				throw LedgerException.idempotencyKeyReused(planned.idempotencyKey());
			}
			return new CommandResult(TransactionView.of(earlier.get(), currencies), true);
		}

		// 4. Apply: post-lock checks, then every posting to its locked account (throws if funds are short).
		if (planned.externalRef() != null && journal.existsByExternalRef(planned.externalRef())) {
			throw LedgerException.externalRefAlreadyUsed(planned.externalRef());
		}
		List<AppliedPosting> postings = planned.legs().stream()
				.map(leg -> locked.get(leg.accountId()).apply(leg.direction(), leg.amount()))
				.toList();

		// 5. Lock the chain head LAST (nothing locks it before an account). Writes stop while a hold is on.
		LedgerState head = ledgerState.lockHead();
		if (head.isIntegrityHold()) {
			throw LedgerException.integrityHold();
		}

		// 6. Write: time from the database, taken under the head lock, so chain order and time order agree.
		TxHasher.Input hashed = new TxHasher.Input(head.getChainLength() + 1, head.getHeadHash(), UUID.randomUUID(),
				planned.type(), dbClock.now(), planned.idempotencyKey(), planned.externalRef(), null, null,
				planned.description(), postings);
		String txHash = TxHasher.hash(hashed);
		JournalTransaction tx = new JournalTransaction(hashed, planned.requestHash(), txHash);
		entityManager.persist(tx);
		head.advance(txHash);
		entityManager.flush(); // constraint errors surface here, inside the method

		// 7. Commit happens when the method returns; deferred triggers re-check balance, chain and postings then.
		logAfterCommit(tx);
		return new CommandResult(TransactionView.of(tx, currencies), false);
	}

	private AccountCurrencyView find(UUID accountId) {
		return accounts.findViewById(accountId).orElseThrow(() -> LedgerException.accountNotFound(accountId));
	}

	/** Deposits and withdrawals: customer wallets and the house FX wallets, never the outside world itself. */
	private AccountCurrencyView walletForMovement(UUID accountId) {
		AccountCurrencyView wallet = find(accountId);
		if (wallet.kind() == AccountKind.FUNDING) {
			throw LedgerException.kindNotAllowed(accountId, "CUSTOMER or FX_LIQUIDITY");
		}
		return wallet;
	}

	private static void requireCustomer(AccountCurrencyView account) {
		if (account.kind() != AccountKind.CUSTOMER) {
			throw LedgerException.kindNotAllowed(account.id(), "CUSTOMER");
		}
	}

	/** Trimmed; blank means absent. Applied before fingerprinting and storing. */
	private static String clean(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	/** Logged only once the commit really happened. */
	private static void logAfterCommit(JournalTransaction tx) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				log.info("Committed {} {} at chain index {}", tx.getType(), tx.getId(), tx.getChainIndex());
			}
		});
	}

}
