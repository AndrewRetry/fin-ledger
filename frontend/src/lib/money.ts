import type { components } from '../api/schema'

/** Money as the backend sends it: integer minor units, the currency, and the backend's own display string. */
export type Money = components['schemas']['Money']

/**
 * How many decimals a currency has, read from the backend's display string ("975.00" -> 2, "50000" -> 0),
 * so the console never keeps its own currency table.
 */
export function decimalsOf(money: Pick<Money, 'display'>): number {
  const point = money.display.indexOf('.')
  return point === -1 ? 0 : money.display.length - point - 1
}

/** Largest amount the API accepts (backend/PLAN.md §8.1). */
export const MAX_AMOUNT = 1_000_000_000_000

/**
 * "25.5" with 2 decimals -> 2550. Positive amounts only, as typed by a person; no floating point anywhere.
 * Throws a readable Error for anything the API would reject.
 */
export function toMinor(text: string, decimals: number): number {
  const value = text.trim()
  if (!/^\d+(\.\d*)?$/.test(value)) {
    throw new Error('Enter a positive amount, e.g. 25.50')
  }
  const [whole, fraction = ''] = value.split('.')
  if (fraction.length > decimals) {
    throw new Error(decimals === 0 ? 'This currency has no decimals' : `At most ${decimals} decimals`)
  }
  const minor = BigInt(whole) * 10n ** BigInt(decimals) + BigInt(fraction.padEnd(decimals, '0') || '0')
  if (minor === 0n) {
    throw new Error('The amount must be more than zero')
  }
  if (minor > BigInt(MAX_AMOUNT)) {
    throw new Error('The amount is larger than the ledger accepts')
  }
  return Number(minor)
}

/** toMinor, or null while the text is empty or invalid (or the currency is not known yet). */
export function minorOrNull(text: string, decimals: number | undefined): number | null {
  if (decimals === undefined) {
    return null
  }
  try {
    return toMinor(text, decimals)
  } catch {
    return null
  }
}

/** 2550 with 2 decimals -> "25.50"; -100000 -> "-1000.00". The same text the backend puts in `display`. */
export function fromMinor(minor: number | bigint, decimals: number): string {
  const value = BigInt(minor)
  const sign = value < 0n ? '-' : ''
  const digits = (value < 0n ? -value : value).toString().padStart(decimals + 1, '0')
  if (decimals === 0) {
    return sign + digits
  }
  return `${sign}${digits.slice(0, -decimals)}.${digits.slice(-decimals)}`
}

/** "1000000.00" -> "1,000,000.00", for reading. Works on the text, so precision is never touched. */
export function groupThousands(display: string): string {
  const [whole, fraction] = display.split('.')
  const sign = whole.startsWith('-') ? '-' : ''
  const grouped = whole.replace('-', '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return fraction === undefined ? sign + grouped : `${sign}${grouped}.${fraction}`
}

/** "1,000.00 SGD" */
export function formatMoney(money: Money): string {
  return `${groupThousands(money.display)} ${money.currency}`
}
