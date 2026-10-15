import { Select } from '@mantine/core'

import { type Account, useAllAccounts } from '../api/queries'
import { groupThousands } from '../lib/money'

const GROUPS: { kind: Account['kind']; label: string }[] = [
  { kind: 'CUSTOMER', label: 'Customers' },
  { kind: 'FX_LIQUIDITY', label: 'House FX wallets' },
  { kind: 'FUNDING', label: 'Funding (outside world)' },
]

/**
 * Picks an account by name or id, grouped by kind, showing currency and balance. System accounts are listed too,
 * so the backend's ACCOUNT_KIND_NOT_ALLOWED answer can be seen.
 */
export function AccountSelect({ label, value, onChange, error }: {
  label: string
  value: string | null
  onChange: (id: string | null) => void
  error?: string
}) {
  const accounts = useAllAccounts()
  const data = GROUPS.map((group) => ({
    group: group.label,
    items: (accounts.data ?? [])
      .filter((account) => account.kind === group.kind)
      .map((account) => ({
        value: account.id,
        label: `${account.name} · ${groupThousands(account.balance.display)} ${account.currency}`,
      })),
  })).filter((group) => group.items.length > 0)

  return (
    <Select
      label={label}
      placeholder={accounts.isPending ? 'Loading accounts…' : 'Search by name'}
      data={data}
      value={value}
      onChange={onChange}
      searchable
      clearable
      nothingFoundMessage="No account matches"
      error={error}
      maxDropdownHeight={320}
    />
  )
}
