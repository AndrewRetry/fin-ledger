import { Badge, Button, Code, Group, Paper, PasswordInput, Stack, Text, Textarea, Title } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconKey, IconLock, IconLockOpen, IconPlugConnected } from '@tabler/icons-react'
import { type FormEvent, useState } from 'react'

import { ApiProblem } from '../api/problem'
import { useAdminActions, useHoldStatus } from '../api/queries'
import { ProblemAlert } from '../components/ProblemAlert'
import { ledgerStatus, useLedgerStatus } from '../lib/ledgerStatus'
import { formatTime } from '../lib/time'

export function AdminPage() {
  return (
    <Stack maw={760}>
      <Title order={2}>Admin</Title>
      <AdminKeyCard />
      <HoldCard />
      <Paper withBorder p="md">
        <Text fw={600} mb={4}>Trying the hold locally</Text>
        <Text size="sm">
          Run <Code>./backend/scripts/tamper-demo.sh</Code>. It edits a posting directly in the database, as only a
          superuser could, then runs the integrity checks: they find it and turn the hold on. Releasing the hold here
          does not repair the data, so the next integrity run turns it back on; <Code>docker compose down -v</Code>
          {' '}starts a clean ledger.
        </Text>
      </Paper>
    </Stack>
  )
}

/** The key lives in this tab's sessionStorage only and is sent only to /admin endpoints. */
function AdminKeyCard() {
  const { adminKey } = useLedgerStatus()
  const [value, setValue] = useState(adminKey ?? '')
  const test = useHoldStatus(Boolean(adminKey))

  function save(event: FormEvent) {
    event.preventDefault()
    ledgerStatus.setAdminKey(value.trim() || null)
    test.refetch()
  }

  return (
    <Paper withBorder p="md">
      <form onSubmit={save}>
        <Stack gap="xs">
          <Group justify="space-between">
            <Text fw={600}>Admin key</Text>
            {adminKey && test.isSuccess && <Badge color="teal" variant="light">Accepted</Badge>}
            {adminKey && test.isError && <Badge color="red" variant="light">Rejected</Badge>}
            {!adminKey && <Badge color="gray" variant="light">Not set</Badge>}
          </Group>
          <Text size="sm" c="dimmed">
            Needed for the integrity hold and for adding exchange rates. With <Code>docker compose up</Code> it is{' '}
            <Code>dev-admin</Code>. Kept for this browser tab only.
          </Text>
          <Group align="flex-end">
            <PasswordInput label="X-Admin-Key" value={value} onChange={(event) => setValue(event.currentTarget.value)}
              leftSection={<IconKey size={16} />} style={{ flex: 1 }} />
            <Button type="submit" leftSection={<IconPlugConnected size={16} />} loading={test.isFetching}>
              Save and test
            </Button>
            {adminKey && (
              <Button variant="default" onClick={() => { ledgerStatus.setAdminKey(null); setValue('') }}>
                Forget
              </Button>
            )}
          </Group>
          {adminKey && test.error && <ProblemAlert error={test.error} />}
        </Stack>
      </form>
    </Paper>
  )
}

function HoldCard() {
  const { adminKey, held } = useLedgerStatus()
  const status = useHoldStatus(Boolean(adminKey))
  const { releaseHold } = useAdminActions()
  const [reason, setReason] = useState('')
  const problem = releaseHold.error instanceof ApiProblem ? releaseHold.error : null

  function release(event: FormEvent) {
    event.preventDefault()
    releaseHold.mutate(reason, {
      onSuccess: () => {
        setReason('')
        notifications.show({ color: 'teal', title: 'Hold released', message: 'Writes are open again.' })
      },
    })
  }

  return (
    <Paper withBorder p="md">
      <Stack gap="xs">
        <Group justify="space-between">
          <Text fw={600}>Integrity hold</Text>
          {held === true && <Badge color="orange" leftSection={<IconLock size={12} />}>On</Badge>}
          {held === false && <Badge color="teal" variant="light" leftSection={<IconLockOpen size={12} />}>Off</Badge>}
        </Group>
        <Text size="sm" c="dimmed">
          When an integrity check or a month-end close finds a discrepancy, the ledger stops moving money until an
          administrator has looked and releases it with a reason.
        </Text>
        {!adminKey && <Text size="sm">Set the admin key above to see the reason and to release the hold.</Text>}
        {status.data?.held && (
          <Paper withBorder p="sm" bg="var(--mantine-color-orange-light)">
            <Text size="sm"><b>Reason:</b> {status.data.reason}</Text>
            <Text size="sm"><b>Since:</b> {formatTime(status.data.since)}</Text>
          </Paper>
        )}
        {adminKey && status.data?.held && (
          <form onSubmit={release}>
            <Stack gap="xs">
              <Textarea label="Why it is safe to release" placeholder="Checked: discrepancy explained and fixed"
                value={reason} onChange={(event) => setReason(event.currentTarget.value)} maxLength={500} autosize
                minRows={2} error={problem?.fieldError('reason')} />
              {problem && problem.errors.length === 0 && <ProblemAlert error={problem} />}
              <Group justify="flex-end">
                <Button type="submit" color="orange" loading={releaseHold.isPending} disabled={!reason.trim()}
                  leftSection={<IconLockOpen size={16} />}>
                  Release hold
                </Button>
              </Group>
            </Stack>
          </form>
        )}
      </Stack>
    </Paper>
  )
}
