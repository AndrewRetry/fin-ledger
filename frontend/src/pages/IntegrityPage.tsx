import { Button, Group, Paper, Select, Stack, Tabs, Text, TextInput, Title } from '@mantine/core'
import { IconCalendarCheck, IconHistory, IconSearch, IconShieldCheck } from '@tabler/icons-react'
import { type FormEvent, useState } from 'react'

import { useIntegrityActions, useStoredRun } from '../api/queries'
import { IntegrityReportView } from '../components/IntegrityReportView'
import { ProblemAlert } from '../components/ProblemAlert'

export function IntegrityPage() {
  return (
    <Stack>
      <Title order={2}>Integrity</Title>
      <Text c="dimmed" size="sm">
        The ledger re-adds everything from scratch and compares it with what it stored. Any discrepancy turns on the
        integrity hold, which stops all money movement until an administrator releases it.
      </Text>
      <Tabs defaultValue="checks" keepMounted>
        <Tabs.List mb="md">
          <Tabs.Tab value="checks" leftSection={<IconShieldCheck size={16} />}>Integrity checks</Tabs.Tab>
          <Tabs.Tab value="month-end" leftSection={<IconCalendarCheck size={16} />}>Month-end</Tabs.Tab>
          <Tabs.Tab value="stored" leftSection={<IconHistory size={16} />}>Stored run</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="checks"><ChecksTab /></Tabs.Panel>
        <Tabs.Panel value="month-end"><MonthEndTab /></Tabs.Panel>
        <Tabs.Panel value="stored"><StoredRunTab /></Tabs.Panel>
      </Tabs>
    </Stack>
  )
}

function ChecksTab() {
  const { runChecks } = useIntegrityActions()
  return (
    <Stack>
      <Paper withBorder p="md">
        <Group justify="space-between">
          <Text size="sm" maw={720}>
            Debits equal credits per currency, every transaction balances, every cached balance equals its postings,
            money in wallets equals money deposited, and every link of the hash chain is recomputed. Also runs every
            10 minutes on its own.
          </Text>
          <Button leftSection={<IconShieldCheck size={16} />} loading={runChecks.isPending}
            onClick={() => runChecks.mutate()}>
            Run integrity checks
          </Button>
        </Group>
      </Paper>
      {runChecks.error && <ProblemAlert error={runChecks.error} />}
      {runChecks.data && <IntegrityReportView report={runChecks.data} />}
    </Stack>
  )
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October',
  'November', 'December'].map((label, i) => ({ value: String(i + 1).padStart(2, '0'), label }))

/** Months follow Singapore time on the backend; this only picks which month to ask for. */
function MonthEndTab() {
  const { closeMonth } = useIntegrityActions()
  const [now] = useState(() => new Date())
  const [year, setYear] = useState<string | null>(String(now.getFullYear()))
  const [month, setMonth] = useState<string | null>(String(now.getMonth() + 1).padStart(2, '0'))
  const years = [-2, -1, 0, 1].map((offset) => String(now.getFullYear() + offset))

  function close(event: FormEvent) {
    event.preventDefault()
    if (year && month) {
      closeMonth.mutate(`${year}-${month}`)
    }
  }

  return (
    <Stack>
      <Paper withBorder p="md">
        <form onSubmit={close}>
          <Stack gap="sm">
            <Text size="sm" maw={760}>
              For every account: last month's closing + this month's credits − debits must land exactly on the balance
              recorded at month end. A clean close of a finished month freezes a snapshot per account; a month still in
              progress gives a provisional report and freezes nothing; a month that hasn't started is refused.
            </Text>
            <Group align="flex-end">
              <Select label="Month" data={MONTHS} value={month} onChange={setMonth} allowDeselect={false} w={160} />
              <Select label="Year" data={years} value={year} onChange={setYear} allowDeselect={false} w={110} />
              <Button type="submit" leftSection={<IconCalendarCheck size={16} />} loading={closeMonth.isPending}>
                Reconcile {year}-{month}
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>
      {closeMonth.error && <ProblemAlert error={closeMonth.error} />}
      {closeMonth.data && <IntegrityReportView report={closeMonth.data} />}
    </Stack>
  )
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

function StoredRunTab() {
  const [input, setInput] = useState('')
  const [runId, setRunId] = useState<string | null>(null)
  const run = useStoredRun(runId)
  const value = input.trim().toLowerCase()

  function open(event: FormEvent) {
    event.preventDefault()
    setRunId(value)
  }

  return (
    <Stack>
      <Paper withBorder p="md">
        <form onSubmit={open}>
          <Group align="flex-end">
            <TextInput label="Run id" placeholder="Copy one from a report's Run id" value={input}
              onChange={(event) => setInput(event.currentTarget.value)} ff="monospace" style={{ flex: 1 }}
              error={value && !UUID.test(value) ? 'A run id looks like 8398f93a-24c5-3661-8e9a-33d54273d824' : undefined} />
            <Button type="submit" disabled={!UUID.test(value)} leftSection={<IconSearch size={16} />}
              loading={run.isFetching}>
              Open
            </Button>
          </Group>
        </form>
      </Paper>
      {run.error && <ProblemAlert error={run.error} />}
      {run.data && <IntegrityReportView report={run.data} />}
    </Stack>
  )
}
