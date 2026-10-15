package com.andrewretry.ledger.account.dto;

import java.util.List;
import java.util.UUID;

public record HistoryPage(UUID accountId, String currency, List<HistoryItem> items, int page, int size,
		long totalItems, int totalPages) {
}
