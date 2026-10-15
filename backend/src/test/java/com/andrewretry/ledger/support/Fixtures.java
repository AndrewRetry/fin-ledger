package com.andrewretry.ledger.support;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Accounts for a test, created through the API. Every test makes its own (the tables are append-only). */
public final class Fixtures {

	private final Api api;

	public Fixtures(Api api) {
		this.api = api;
	}

	public UUID customer(String name, String currency) {
		Api.Response created = api.post("/accounts", "{\"name\": \"" + name + "\", \"currency\": \"" + currency + "\"}");
		return UUID.fromString(created.json().path("id").asString());
	}

	public UUID funded(String name, String currency, long amountMinor) {
		UUID id = customer(name, currency);
		deposit(id, amountMinor);
		return id;
	}

	public Api.Response deposit(UUID accountId, long amountMinor) {
		return api.post("/deposits", "{\"accountId\": \"" + accountId + "\", \"amount\": " + amountMinor + "}");
	}

	public long balance(UUID accountId) {
		return api.get("/accounts/" + accountId).json().path("balance").path("amount").asLong();
	}

	/** A FUNDING or FX_LIQUIDITY account; migrations create them first, so they are on the first page. */
	public UUID systemAccount(String kind, String currency) {
		for (JsonNode account : api.get("/accounts?size=200").json().path("items")) {
			if (account.path("kind").asString().equals(kind) && account.path("currency").asString().equals(currency)) {
				return UUID.fromString(account.path("id").asString());
			}
		}
		throw new IllegalStateException("No " + kind + " account for " + currency);
	}

}
