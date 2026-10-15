import { Alert, Code, Text } from '@mantine/core'
import { IconShieldCheck, IconShieldX } from '@tabler/icons-react'
import { useEffect, useState } from 'react'

import type { Transaction } from '../api/money'
import { computeTxHash } from '../lib/txHash'

/** Recomputes the transaction's hash in the browser and compares it with the stored one. */
export function HashCheck({ tx }: { tx: Transaction }) {
  const [computed, setComputed] = useState<string | null>(null)

  useEffect(() => {
    let current = true
    computeTxHash(tx).then((hash) => current && setComputed(hash))
    return () => {
      current = false
    }
  }, [tx])

  if (computed === null) {
    return null
  }
  const matches = computed === tx.txHash
  return (
    <Alert
      variant="light"
      color={matches ? 'teal' : 'red'}
      icon={matches ? <IconShieldCheck size={18} /> : <IconShieldX size={18} />}
      title={matches ? 'Hash verified in your browser' : 'Hash does not match'}
    >
      <Text size="sm">
        {matches
          ? 'SHA-256 of this transaction\'s fields, recomputed here from what the API returned, equals the stored ' +
            'txHash. Any edit to these fields, or to an earlier transaction, would break it.'
          : 'The recomputed hash differs from the stored txHash: the stored data is not what was hashed.'}
      </Text>
      {!matches && (
        <Text size="xs" mt={4}>
          recomputed <Code>{computed}</Code>
        </Text>
      )}
    </Alert>
  )
}
