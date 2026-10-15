import { describe, expect, it } from 'vitest'

import { convertMinor } from '../../src/lib/fxPreview'

/** backend/PLAN.md Appendix A: the same vectors the backend's FxConversionTest uses. */
describe('convertMinor', () => {
  it.each([
    [10000, '0.775', 2, 2, 7750],
    [999, '151.51515', 2, 0, 1513],
    [1, '0.0066', 0, 2, 0],
    [12345, '1.4967742', 2, 2, 18477],
    [10000, '151.234567', 2, 0, 15123],
    [250000, '0.0066118', 0, 2, 165295],
    [12345, '0.68123', 2, 2, 8409],
  ])('%i at %s (%i -> %i decimals) = %i', (from, rate, fromDecimals, toDecimals, expected) => {
    expect(convertMinor(from, rate, fromDecimals, toDecimals)).toBe(expected)
  })

  it('handles a whole-number rate', () => {
    expect(convertMinor(10000, '2', 2, 2)).toBe(20000)
  })
})
