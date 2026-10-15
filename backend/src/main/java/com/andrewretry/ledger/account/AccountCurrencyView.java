package com.andrewretry.ledger.account;

import java.util.UUID;

/**
 * What a command needs to know about an account before it has locked it. A projection, never a managed entity:
 * loading an Account first would make Hibernate return that stale instance from the later locking query.
 */
public record AccountCurrencyView(UUID id, String currency, AccountKind kind) {
}
