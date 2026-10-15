import { Alert, Anchor, Text } from '@mantine/core'
import { IconLock } from '@tabler/icons-react'
import { Link } from 'react-router'

import { useHoldStatus } from '../api/queries'
import { useLedgerStatus } from '../lib/ledgerStatus'

/**
 * Across the top of every page while responses say X-Ledger-Integrity: HOLD. With the admin key set, it also
 * shows why the hold is on.
 */
export function HoldBanner() {
  const { held, adminKey } = useLedgerStatus()
  const status = useHoldStatus(Boolean(held && adminKey))
  if (!held) {
    return null
  }
  return (
    <Alert color="orange" variant="filled" icon={<IconLock size={18} />} title="Writes are stopped by an integrity hold"
      mb="md">
      <Text size="sm">
        A check found numbers that don't add up, so the ledger refuses to move money (503) until an administrator
        releases it. Reads still work.
        {status.data?.reason && <> Reason: {status.data.reason}.</>}{' '}
        <Anchor component={Link} to="/admin" c="white" underline="always" fw={600}>
          Open Admin
        </Anchor>
      </Text>
    </Alert>
  )
}
