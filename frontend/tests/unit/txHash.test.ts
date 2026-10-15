import { describe, expect, it } from 'vitest'

import type { Transaction } from '../../src/api/money'
import { canonical, computeTxHash, epochMicros } from '../../src/lib/txHash'

/** backend/PLAN.md Appendix A: the vectors the backend's TxHasherTest and an independent Python version agree on. */
const id = (suffix: string) => `0192f0a0-0000-7000-8000-${suffix.padStart(12, '0')}`
const A = id('a'), B = id('b'), FXLIQ_SGD = id('c'), FXLIQ_USD = id('d'), C = id('e'), FUNDING_SGD = id('f')

function posting(accountId: string, currency: string, direction: 'DEBIT' | 'CREDIT', amount: number, version: number,
  balanceAfter: number) {
  return {
    accountId,
    direction,
    amount: { amount, currency, display: '' },
    balanceAfter: { amount: balanceAfter, currency, display: '' },
    accountVersion: version,
  }
}

function tx(fields: Partial<Transaction> & Pick<Transaction, 'chainIndex' | 'prevHash' | 'id' | 'type' | 'createdAt'
  | 'idempotencyKey' | 'postings'>): Transaction {
  return {
    status: 'POSTED', externalRef: null, reversesId: null, fx: null, description: null, txHash: '', ...fields,
  } as unknown as Transaction
}

const tx1 = tx({
  chainIndex: 1, prevHash: '0'.repeat(64), id: id('1'), type: 'TRANSFER', createdAt: '2026-10-14T03:00:00.123456Z',
  idempotencyKey: 'demo-key-1', description: 'Lunch split',
  postings: [posting(B, 'SGD', 'CREDIT', 2500, 1, 2500), posting(A, 'SGD', 'DEBIT', 2500, 3, 7500)], // B first on purpose
})
const H1 = '5fc81c8d533a218242da8ff6709fb74924c8fcba6f91dc6a713f5defb53194ae'
const H2 = '989d8af316373cf36472b1692966dcac1103885a6abc6be5ddd8cde877e8b815'
const H3 = 'd9e3f0b83c2999e3c1984ba4ea9de3155fc2602bcea1f16c7077fda34f14c32d'
const H4 = '883c8817a5a05ec62999d501b93e5f1638adbf86f08e60ec934c619920cee9c1'

describe('txHash', () => {
  it('encodes tx1 canonically', () => {
    const bytes = canonical(tx1)
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
    expect(bytes.length).toBe(362)
    expect(hex.startsWith('0000000c4c45444745522d54582d5632000000013100000040303030303030303030303030303030')).toBe(true)
  })

  it('hashes the four chained vectors', async () => {
    expect(await computeTxHash(tx1)).toBe(H1)
    expect(await computeTxHash(tx({
      chainIndex: 2, prevHash: H1, id: id('2'), type: 'REVERSAL', createdAt: '2026-10-14T03:00:01Z',
      idempotencyKey: 'demo-key-2', reversesId: id('1'), description: 'Entered twice',
      postings: [posting(A, 'SGD', 'CREDIT', 2500, 4, 10000), posting(B, 'SGD', 'DEBIT', 2500, 2, 0)],
    }))).toBe(H2)
    expect(await computeTxHash(tx({
      chainIndex: 3, prevHash: H2, id: id('3'), type: 'FX_TRANSFER', createdAt: '2026-10-14T03:00:02.5Z',
      idempotencyKey: 'demo-key-3',
      fx: { rateId: id('f1'), base: 'SGD', quote: 'USD', rate: '0.775', source: 'SEED' },
      postings: [posting(C, 'USD', 'CREDIT', 7750, 1, 7750), posting(FXLIQ_USD, 'USD', 'DEBIT', 7750, 2, 99992250),
        posting(A, 'SGD', 'DEBIT', 10000, 5, 0), posting(FXLIQ_SGD, 'SGD', 'CREDIT', 10000, 1, 10000)],
    }))).toBe(H3)
    expect(await computeTxHash(tx({
      chainIndex: 4, prevHash: H3, id: id('4'), type: 'DEPOSIT', createdAt: '2026-10-14T03:00:03Z',
      idempotencyKey: 'demo-key-4', externalRef: 'fb-tx-1001', description: 'Fireblocks deposit',
      postings: [posting(FUNDING_SGD, 'SGD', 'DEBIT', 50000, 7, -150000), posting(A, 'SGD', 'CREDIT', 50000, 6, 50000)],
    }))).toBe(H4)
  })

  it('reads microseconds from the timestamp text', () => {
    expect(epochMicros('2026-10-14T03:00:00.123456Z')).toBe('1791946800123456')
    expect(epochMicros('2026-10-14T03:00:02.5Z')).toBe('1791946802500000')
    expect(epochMicros('2026-10-14T03:00:01Z')).toBe('1791946801000000')
  })
})
