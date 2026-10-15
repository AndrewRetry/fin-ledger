/**
 * What an FX transfer should pay out, the same way the backend computes it (FxConversion): exact maths, then
 * rounded down. Only a preview — the server's answer is the one that counts.
 *
 * fromMinor x rate, moved from the source currency's decimals to the destination's, floored.
 * Example: USD 9.99 (999) at 151.51515, 2 -> 0 decimals = 1513.63... -> 1513 yen.
 */
export function convertMinor(fromMinor: number, rate: string, fromDecimals: number, toDecimals: number): number {
  const [whole, fraction = ''] = rate.split('.')
  const rateDigits = BigInt(whole + fraction)
  const rateScale = fraction.length
  const shift = toDecimals - fromDecimals
  const numerator = BigInt(fromMinor) * rateDigits * 10n ** BigInt(Math.max(shift, 0))
  const denominator = 10n ** BigInt(rateScale + Math.max(-shift, 0))
  return Number(numerator / denominator) // BigInt division truncates; amounts are positive, so that is floor
}
