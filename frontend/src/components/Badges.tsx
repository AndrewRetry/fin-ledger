import { Badge } from '@mantine/core'

/** A table cell may squeeze a badge below its text width; this keeps the whole word visible. */
const NEVER_CUT = { minWidth: 'max-content' }

const KIND_COLOR: Record<string, string> = { CUSTOMER: 'blue', FX_LIQUIDITY: 'violet', FUNDING: 'gray' }
const TYPE_COLOR: Record<string, string> = {
  DEPOSIT: 'teal',
  WITHDRAWAL: 'orange',
  TRANSFER: 'blue',
  FX_TRANSFER: 'violet',
  REVERSAL: 'gray',
}

/** CUSTOMER, FX_LIQUIDITY or FUNDING. */
export function KindBadge({ kind }: { kind: string }) {
  return (
    <Badge variant="light" color={KIND_COLOR[kind] ?? 'gray'} radius="sm" style={NEVER_CUT}>
      {kind.replace('_', ' ')}
    </Badge>
  )
}

/** DEPOSIT, WITHDRAWAL, TRANSFER, FX_TRANSFER or REVERSAL. */
export function TypeBadge({ type }: { type: string }) {
  return (
    <Badge variant="light" color={TYPE_COLOR[type] ?? 'gray'} radius="sm" style={NEVER_CUT}>
      {type.replace('_', ' ')}
    </Badge>
  )
}
