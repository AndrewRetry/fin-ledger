import { Text, TextInput } from '@mantine/core'

import { toMinor } from '../lib/money'

/**
 * An amount typed in major units ("25.50"), checked live with the exact string maths of toMinor, and shown in
 * minor units underneath so the API contract stays visible. A text field, not a number field: no float rounding.
 */
export function MoneyInput({
  label,
  value,
  onChange,
  currency,
  decimals,
  error,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  currency?: string
  decimals?: number
  error?: string
}) {
  let hint: string | undefined
  let invalid: string | undefined
  if (value.trim() && decimals !== undefined) {
    try {
      hint = `= ${toMinor(value, decimals)} minor units`
    } catch (problem) {
      invalid = (problem as Error).message
    }
  }
  return (
    <TextInput
      label={label}
      placeholder={decimals === 0 ? '5000' : '25.00'}
      inputMode="decimal"
      value={value}
      onChange={(event) => onChange(event.currentTarget.value)}
      rightSection={
        <Text size="sm" c="dimmed">
          {currency}
        </Text>
      }
      rightSectionWidth={52}
      inputWrapperOrder={['label', 'input', 'description', 'error']}
      error={error ?? invalid}
      description={invalid ? undefined : (hint ?? (decimals === undefined ? 'Choose an account first' : ' '))}
      disabled={decimals === undefined}
    />
  )
}
