import type { Transaction } from '../api/money'

/**
 * Recomputes a transaction's tx_hash in the browser from the fields the API returned (backend/PLAN.md §7.6), so
 * anyone can check a transaction without trusting the server's word for it.
 *
 * canonical = field("LEDGER-TX-V2") field(chainIndex) field(prevHash) field(id) field(type) field(createdAtMicros)
 *             field(idempotencyKey) field(externalRef) field(reversesId) field(fxRateId) field(description)
 *             field(postingCount), then per posting sorted by accountId:
 *             field(accountId) field(currency) field(direction) field(amount) field(accountVersion) field(balanceAfter)
 * where field(s) = 4-byte big-endian length of UTF-8(s), then the bytes; absent values are "".
 */
export function canonical(tx: Transaction): Uint8Array {
  const parts: Uint8Array[] = []
  const field = (value: string | number | null | undefined) => {
    const bytes = new TextEncoder().encode(value === null || value === undefined ? '' : String(value))
    const length = new Uint8Array(4)
    new DataView(length.buffer).setUint32(0, bytes.length)
    parts.push(length, bytes)
  }
  field('LEDGER-TX-V2')
  field(tx.chainIndex)
  field(tx.prevHash)
  field(tx.id)
  field(tx.type)
  field(epochMicros(tx.createdAt))
  field(tx.idempotencyKey)
  field(tx.externalRef)
  field(tx.reversesId)
  field(tx.fx?.rateId)
  field(tx.description)
  field(tx.postings.length)
  const sorted = [...tx.postings].sort((a, b) => (a.accountId < b.accountId ? -1 : a.accountId > b.accountId ? 1 : 0))
  for (const posting of sorted) {
    field(posting.accountId)
    field(posting.amount.currency)
    field(posting.direction)
    field(posting.amount.amount)
    field(posting.accountVersion)
    field(posting.balanceAfter.amount)
  }
  const all = new Uint8Array(parts.reduce((total, part) => total + part.length, 0))
  let offset = 0
  for (const part of parts) {
    all.set(part, offset)
    offset += part.length
  }
  return all
}

export async function computeTxHash(tx: Transaction): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', canonical(tx) as BufferSource)
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

/**
 * "2026-10-14T03:00:00.123456Z" -> "1791946800123456". JavaScript dates stop at milliseconds, so the fraction
 * is read from the text.
 */
export function epochMicros(iso: string): string {
  const match = /^(.*?)(?:\.(\d+))?Z$/.exec(iso)
  if (!match) {
    throw new Error(`Unexpected timestamp: ${iso}`)
  }
  const seconds = BigInt(Date.parse(`${match[1]}Z`) / 1000)
  const fraction = (match[2] ?? '').padEnd(6, '0').slice(0, 6)
  return (seconds * 1_000_000n + BigInt(fraction)).toString()
}
