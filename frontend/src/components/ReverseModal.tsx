import { Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconArrowBackUp } from '@tabler/icons-react'
import { type FormEvent, useState } from 'react'
import { useNavigate } from 'react-router'

import { useMoneyRequest } from '../api/money'
import { ApiProblem } from '../api/problem'
import { useIdempotencyKey } from '../lib/useIdempotencyKey'
import { IdempotencyField } from './IdempotencyField'
import { ProblemAlert } from './ProblemAlert'

/**
 * Posts the mirror image of a transaction. The backend refuses a second reversal (409), the reversal of a
 * reversal (422), and a reversal the recipient can no longer pay (422); those answers are shown here as they are.
 */
export function ReverseModal({ transactionId, opened, onClose }: {
  transactionId: string
  opened: boolean
  onClose: () => void
}) {
  const [reason, setReason] = useState('')
  const idempotency = useIdempotencyKey()
  const reverse = useMoneyRequest()
  const navigate = useNavigate()
  const problem = reverse.error instanceof ApiProblem ? reverse.error : null

  function close() {
    reverse.reset()
    onClose()
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    reverse.mutate(
      { request: { kind: 'reverse', transactionId, body: { reason } }, key: idempotency.key },
      {
        onSuccess: ({ tx, replayed }) => {
          notifications.show({
            color: replayed ? 'gray' : 'teal',
            title: replayed ? 'Replayed, nothing reversed again' : 'Reversed',
            message: 'Every posting was mirrored: same accounts and amounts, debit and credit swapped.',
          })
          close()
          navigate(`/transactions/${tx.id}`)
        },
      },
    )
  }

  return (
    <Modal opened={opened} onClose={close} title="Reverse this transaction" centered size="lg">
      <form onSubmit={submit}>
        <Stack>
          <Text size="sm" c="dimmed">
            A transaction is never edited. Reversing posts its exact opposite, at the original amounts (and rate, for
            FX), and links the two.
          </Text>
          <Textarea
            label="Reason"
            placeholder="Customer dispute"
            value={reason}
            onChange={(event) => setReason(event.currentTarget.value)}
            error={problem?.fieldError('reason')}
            maxLength={280}
            autosize
            minRows={2}
            data-autofocus
          />
          <IdempotencyField value={idempotency.key} onChange={idempotency.setKey} onRenew={idempotency.renew} />
          {problem && problem.errors.length === 0 && <ProblemAlert error={problem} />}
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              Cancel
            </Button>
            <Button type="submit" color="orange" loading={reverse.isPending} disabled={!reason.trim()}
              leftSection={<IconArrowBackUp size={16} />}>
              Reverse
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  )
}
