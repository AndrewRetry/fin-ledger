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
import com.andrewretry.ledger.common.Text;
import com.andrewretry.ledger.common.error.LedgerException;
import com.andrewretry.ledger.fx.FxConversion;
import com.andrewretry.ledger.fx.FxRate;
import com.andrewretry.ledger.fx.FxRateService;
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
	private final FxRateService fxRates;

	public LedgerCommandService(AccountRepository accounts, JournalTransactionRepository journal,
			LedgerStateRepository ledgerState, DbClock dbClock, Currencies currencies, EntityManager entityManager,
			FxRateService fxRates) {
		this.accounts = accounts;
		this.journal = journal;
		this.ledgerState = ledgerState;
		this.dbClock = dbClock;
		this.currencies = currencies;
		this.entityManager = entityManager;
		this.fxRates = fxRates;
	}

	/** One posting to be applied: which account, which way, how much. */
	private record Leg(UUID accountId, Direction direction, long amount) {
	}

	/**
	 * The outcome of step 1: everything needed to post, decided before any lock is taken.
	 * {@code reverses} is the original transaction when this is a reversal, {@code fxRate} the rate used when this
	 * is an FX transfer; both are null otherwise.
	 */
	private record Planned(TransactionType type, String idempotencyKey, String requestHash, String externalRef,
			String description, List<Leg> legs, JournalTransaction reverses, FxRate fxRate) {

		Planned(TransactionType type, String idempotencyKey, String requestHash, String externalRef,
				String description, List<Leg> legs) {
			this(type, idempotencyKey, requestHash, externalRef, description, legs, null, null);
		}

		Set<UUID> accountIds() {
			return legs.stream().map(Leg::accountId).collect(Collectors.toSet());
		}
	}

	@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
	public CommandResult deposit(String idempotencyKey, UUID accountId, long amount, String description,
			String externalRef) {
		description = Text.clean(description);
		externalRef = Text.clean(externalRef);
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
		description = Text.clean(description);
		externalRef = Text.clean(externalRef);
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
		currency = Text.clean(currency);
		description = Text.clean(description);
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
		String fingerprint = RequestFingerprint.transfer(sourceId, destinationId, amount, currency, description);
		if (source.currency().equals(destination.currency())) {
			return post(new Planned(TransactionType.TRANSFER, idempotencyKey, fingerprint, null, description,
					List.of(new Leg(sourceId, Direction.DEBIT, amount), new Leg(destinationId, Direction.CREDIT, amount))));
		}

		// FX transfer (PLAN.md §7.3): two trades with the house. The sender pays the house's wallet in the source
		// currency; the house's wallet in the destination currency pays the recipient. Each currency balances alone.
		String from = source.currency();
		String to = destination.currency();
		FxRate rate = fxRates.latest(from, to);
		long converted = FxConversion.convert(amount, rate.getRate(), currencies.minorUnits(from), currencies.minorUnits(to));
		if (converted == 0) {
			throw LedgerException.fxAmountTooSmall(amount, from, to);
		}
		UUID houseFrom = accounts.findSystemAccount(AccountKind.FX_LIQUIDITY, from).id();
		UUID houseTo = accounts.findSystemAccount(AccountKind.FX_LIQUIDITY, to).id();
		return post(new Planned(TransactionType.FX_TRANSFER, idempotencyKey, fingerprint, null, description,
				List.of(new Leg(sourceId, Direction.DEBIT, amount),
						new Leg(houseFrom, Direction.CREDIT, amount),
						new Leg(houseTo, Direction.DEBIT, converted),
						new Leg(destinationId, Direction.CREDIT, converted)),
				null, rate));
	}

	@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
	public CommandResult reverse(String idempotencyKey, UUID transactionId, String reason) {
		reason = Text.clean(reason);
		// 1. Load the original with its postings. Postings never change, so reading them before locking is safe.
		//    The reversal mirrors every posting: same account and amount, DEBIT and CREDIT swapped.
		JournalTransaction original = journal.findById(transactionId)
				.orElseThrow(() -> LedgerException.transactionNotFound(transactionId));
		List<Leg> mirrored = original.getPostings().stream()
				.map(p -> new Leg(p.accountId(), opposite(p.direction()), p.amount()))
				.toList();
		return post(new Planned(TransactionType.REVERSAL, idempotencyKey,
				RequestFingerprint.reversal(transactionId, reason), null, reason, mirrored, original, null));
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
			JournalTransaction replayed = earlier.get();
			return new CommandResult(TransactionView.of(replayed, fxRates.usedBy(replayed), currencies), true);
		}

		// 4. Apply: post-lock checks (external reference unused; a reversal's original not already reversed),
		//    then every posting to its locked account (throws if funds are short).
		if (planned.externalRef() != null && journal.existsByExternalRef(planned.externalRef())) {
			throw LedgerException.externalRefAlreadyUsed(planned.externalRef());
		}
		if (planned.reverses() != null) {
			requireReversible(planned.reverses());
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
				planned.type(), dbClock.now(), planned.idempotencyKey(), planned.externalRef(),
				planned.reverses() == null ? null : planned.reverses().getId(),
				planned.fxRate() == null ? null : planned.fxRate().getId(), planned.description(), postings);
		String txHash = TxHasher.hash(hashed);
		JournalTransaction tx = new JournalTransaction(hashed, planned.requestHash(), txHash);
		entityManager.persist(tx);
		head.advance(txHash);
		entityManager.flush(); // constraint errors surface here, inside the method

		// 7. Commit happens when the method returns; deferred triggers re-check balance, chain and postings then.
		logAfterCommit(tx);
		return new CommandResult(TransactionView.of(tx, planned.fxRate(), currencies), false);
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

	/** A reversal can never itself be reversed, and a transaction is reversed at most once. */
	private void requireReversible(JournalTransaction original) {
		if (original.getType() == TransactionType.REVERSAL) {
			throw LedgerException.cannotReverseReversal(original.getId());
		}
		if (journal.existsByReversesId(original.getId())) {
			throw LedgerException.alreadyReversed(original.getId());
		}
	}

	private static Direction opposite(Direction direction) {
		return direction == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT;
	}

	private static void requireCustomer(AccountCurrencyView account) {
		if (account.kind() != AccountKind.CUSTOMER) {
			throw LedgerException.kindNotAllowed(account.id(), "CUSTOMER");
		}
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
