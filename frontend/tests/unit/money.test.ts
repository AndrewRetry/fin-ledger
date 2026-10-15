import { describe, expect, it } from 'vitest'

import { decimalsOf, formatMoney, fromMinor, groupThousands, toMinor } from '../../src/lib/money'

describe('decimalsOf', () => {
  it('reads the decimals from the backend display string', () => {
    expect(decimalsOf({ display: '975.00' })).toBe(2)
    expect(decimalsOf({ display: '50000' })).toBe(0)
    expect(decimalsOf({ display: '-1000.00' })).toBe(2)
  })
})

describe('toMinor', () => {
  it('converts typed amounts to integer minor units', () => {
    expect(toMinor('25.50', 2)).toBe(2550)
    expect(toMinor('25.5', 2)).toBe(2550)
    expect(toMinor('25', 2)).toBe(2500)
    expect(toMinor('25.', 2)).toBe(2500)
    expect(toMinor(' 0.01 ', 2)).toBe(1)
    expect(toMinor('50000', 0)).toBe(50000)
  })

  it('is exact where floating point is not', () => {
    expect(toMinor('0.29', 2)).toBe(29) // 0.29 * 100 = 28.999999999999996 in floating point
    expect(toMinor('1.15', 2)).toBe(115)
    expect(toMinor('10000000000.00', 2)).toBe(1_000_000_000_000)
  })

  it('rejects what the API would reject', () => {
    expect(() => toMinor('25.555', 2)).toThrow('At most 2 decimals')
    expect(() => toMinor('10.5', 0)).toThrow('no decimals')
    expect(() => toMinor('0', 2)).toThrow('more than zero')
    expect(() => toMinor('-5', 2)).toThrow('positive amount')
    expect(() => toMinor('1e3', 2)).toThrow('positive amount')
    expect(() => toMinor('', 2)).toThrow('positive amount')
    expect(() => toMinor('10000000000.01', 2)).toThrow('larger than the ledger accepts')
  })
})

describe('fromMinor', () => {
  it('matches the backend display format', () => {
    expect(fromMinor(2500, 2)).toBe('25.00')
    expect(fromMinor(5, 2)).toBe('0.05')
    expect(fromMinor(0, 2)).toBe('0.00')
    expect(fromMinor(1500, 0)).toBe('1500')
    expect(fromMinor(-100000, 2)).toBe('-1000.00')
  })

  it('round-trips with toMinor', () => {
    for (const text of ['0.01', '25.50', '999999.99', '1234567.89']) {
      expect(fromMinor(toMinor(text, 2), 2)).toBe(text)
    }
  })
})

describe('formatting', () => {
  it('groups thousands without touching the digits', () => {
    expect(groupThousands('1000000.00')).toBe('1,000,000.00')
    expect(groupThousands('-150000.00')).toBe('-150,000.00')
    expect(groupThousands('50000')).toBe('50,000')
    expect(groupThousands('999.99')).toBe('999.99')
  })

  it('shows money with its currency', () => {
    expect(formatMoney({ amount: 100000, currency: 'SGD', display: '1000.00' })).toBe('1,000.00 SGD')
  })
})
