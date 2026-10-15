package com.andrewretry.ledger.journal.dto;

/** The newest link of the hash chain. An auditor can record it outside the system. */
public record LedgerHead(long chainLength, String headHash) {
}
