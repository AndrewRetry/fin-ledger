import { Anchor, Badge, Button, Group, Paper, Select, SimpleGrid, Skeleton, Stack, Table, Text, TextInput, Title } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconPlus } from '@tabler/icons-react'
import { type FormEvent, useState } from 'react'
import { Link } from 'react-router'

import { ApiProblem } from '../api/problem'
import { useAdminActions, useFxRates } from '../api/queries'
import { IdCell } from '../components/IdCell'
import { ProblemAlert } from '../components/ProblemAlert'
import { useLedgerStatus } from '../lib/ledgerStatus'
import { formatTime } from '../lib/time'

const CURRENCIES = ['SGD', 'USD', 'EUR', 'JPY']

export function FxRatesPage() {
  const rates = useFxRates()
  const [added, setAdded] = useState<string | null>(null)

  return (
    <Stack>
      <Title order={2}>FX rates</Title>
      <Text c="dimmed" size="sm">
        The rate in use for each pair: the newest row. Rates are never changed or deleted; a new rate is a new row,
        and every FX transfer keeps the id of the exact rate it used. 1 base = rate × quote; amounts are rounded down.
      </Text>
      {rates.error && <ProblemAlert error={rates.error} onRetry={() => rates.refetch()} />}
      <SimpleGrid cols={{ base: 1, lg: 3 }} spacing="md">
        <Paper withBorder style={{ gridColumn: 'span 2' }}>
          <Table.ScrollContainer minWidth={620}>
            <Table striped verticalSpacing="xs">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Pair</Table.Th>
                  <Table.Th ta="right">Rate</Table.Th>
                  <Table.Th>Source</Table.Th>
                  <Table.Th>Since</Table.Th>
                  <Table.Th>Id</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rates.isPending && (
                  <Table.Tr><Table.Td colSpan={5}><Skeleton h={200} /></Table.Td></Table.Tr>
                )}
                {rates.data?.map((rate) => (
                  <Table.Tr key={rate.id} bg={rate.id === added ? 'var(--mantine-color-teal-light)' : undefined}>
                    <Table.Td fw={500} style={{ whiteSpace: 'nowrap' }}>{rate.base} → {rate.quote}</Table.Td>
                    <Table.Td ta="right" ff="monospace">{rate.rate}</Table.Td>
                    <Table.Td>
                      <Badge variant="light" color={rate.source === 'MANUAL' ? 'indigo' : 'gray'} radius="sm"
                        style={{ minWidth: 'max-content' }}>
                        {rate.source}
                      </Badge>
                    </Table.Td>
                    <Table.Td><Text size="sm" style={{ whiteSpace: 'nowrap' }}>{formatTime(rate.createdAt)}</Text></Table.Td>
                    <Table.Td><IdCell value={rate.id} /></Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Paper>
        <AddRateForm onAdded={setAdded} />
      </SimpleGrid>
    </Stack>
  )
}

function AddRateForm({ onAdded }: { onAdded: (id: string) => void }) {
  const { adminKey } = useLedgerStatus()
  const { addRate } = useAdminActions()
  const [base, setBase] = useState<string | null>('SGD')
  const [quote, setQuote] = useState<string | null>('USD')
  const [rate, setRate] = useState('')
  const problem = addRate.error instanceof ApiProblem ? addRate.error : null

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!base || !quote) {
      return
    }
    addRate.mutate({ base, quote, rate: rate.trim() }, {
      onSuccess: (created) => {
        onAdded(created.id)
        setRate('')
        notifications.show({ color: 'teal', title: 'Rate added',
          message: `1 ${created.base} = ${created.rate} ${created.quote}, used from the next transfer.` })
      },
    })
  }

  return (
    <Paper withBorder p="md">
      <form onSubmit={submit}>
        <Stack gap="sm">
          <Text fw={600}>Add a rate</Text>
          {!adminKey && (
            <Text size="sm" c="dimmed">
              Needs the admin key. <Anchor component={Link} to="/admin">Set it on the Admin page</Anchor>.
            </Text>
          )}
          <Group grow>
            <Select label="Base" data={CURRENCIES} value={base} onChange={setBase} allowDeselect={false}
              error={problem?.fieldError('base')} disabled={!adminKey} />
            <Select label="Quote" data={CURRENCIES} value={quote} onChange={setQuote} allowDeselect={false}
              error={problem?.fieldError('quote')} disabled={!adminKey} />
          </Group>
          <TextInput label="Rate" placeholder="0.7812" description="1 base buys this many quote; up to 12 decimals"
            value={rate} onChange={(event) => setRate(event.currentTarget.value)} inputMode="decimal"
            error={problem?.fieldError('rate')} disabled={!adminKey} />
          {problem && problem.errors.length === 0 && <ProblemAlert error={problem} />}
          <Button type="submit" leftSection={<IconPlus size={16} />} loading={addRate.isPending}
            disabled={!adminKey || !rate.trim()}>
            Add rate
          </Button>
        </Stack>
      </form>
    </Paper>
  )
}
