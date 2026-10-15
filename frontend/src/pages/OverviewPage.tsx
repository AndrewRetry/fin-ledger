import {
  ActionIcon,
  Anchor,
  Badge,
  Button,
  CopyButton,
  Group,
  List,
  Paper,
  SimpleGrid,
  Skeleton,
  Stack,
  Table,
  Text,
  ThemeIcon,
  Title,
  Tooltip,
} from '@mantine/core'
import {
  IconArrowsExchange,
  IconCheck,
  IconCopy,
  IconKey,
  IconListCheck,
  IconShieldCheck,
  IconX,
} from '@tabler/icons-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'

import { useAllAccounts, useIntegrityActions, useLedgerHead } from '../api/queries'
import { ProblemAlert } from '../components/ProblemAlert'
import { formatMoney } from '../lib/money'

/** The backend creates these on startup with fixed ids (backend/PLAN.md §7.10) when demo data is on. */
const DEMO_ACCOUNTS = [
  { name: 'Alice', id: '8398f93a-24c5-3661-8e9a-33d54273d824' },
  { name: 'Bob', id: '9f75da44-22c8-31e4-9a7d-2bd07518cc4a' },
  { name: 'Carol', id: 'f5da7969-bb24-307c-ad5f-82b42413fc67' },
  { name: 'Kenji', id: '413efdce-9786-318b-be29-69819a82091a' },
]

export function OverviewPage() {
  return (
    <Stack>
      <Title order={2}>Overview</Title>
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <ChainCards />
      </SimpleGrid>
      <SimpleGrid cols={{ base: 1, lg: 3 }}>
        <StartHere />
        <DemoAccounts />
        <IntegrityCard />
      </SimpleGrid>
    </Stack>
  )
}

function ChainCards() {
  const head = useLedgerHead()
  if (head.error) {
    return <ProblemAlert error={head.error} onRetry={() => head.refetch()} />
  }
  return (
    <>
      <Paper withBorder p="md">
        <Text size="sm" c="dimmed">Hash chain length</Text>
        {head.data ? <Text fz={32} fw={600}>{head.data.chainLength}</Text> : <Skeleton h={40} w={120} mt={4} />}
        <Text size="xs" c="dimmed">Every committed transaction is one link.</Text>
      </Paper>
      <Paper withBorder p="md">
        <Text size="sm" c="dimmed">Head hash</Text>
        {head.data ? (
          <Group gap="xs" wrap="nowrap" mt={4}>
            <Text ff="monospace" size="sm" style={{ wordBreak: 'break-all' }}>{head.data.headHash}</Text>
            <CopyButton value={head.data.headHash}>
              {({ copied, copy }) => (
                <Tooltip label={copied ? 'Copied' : 'Copy'} withArrow>
                  <ActionIcon variant="subtle" color="gray" onClick={copy} aria-label="Copy head hash">
                    {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
                  </ActionIcon>
                </Tooltip>
              )}
            </CopyButton>
          </Group>
        ) : (
          <Skeleton h={40} mt={4} />
        )}
        <Text size="xs" c="dimmed">Record it somewhere outside the system: any later edit to history changes it.</Text>
      </Paper>
    </>
  )
}

function StartHere() {
  return (
    <Card title="Start here">
      <List spacing="sm" size="sm" center>
        <Step icon={<IconListCheck size={14} />} to="/scenarios" label="Scenarios">
          run every requirement of the assignment with one click, with the evidence for each step.
        </Step>
        <Step icon={<IconArrowsExchange size={14} />} to="/move" label="Move money">
          transfers (also across currencies), deposits and withdrawals; send one twice to see a replay.
        </Step>
        <Step icon={<IconShieldCheck size={14} />} to="/integrity" label="Integrity">
          prove the totals, verify the hash chain, reconcile a month.
        </Step>
        <Step icon={<IconKey size={14} />} to="/admin" label="Admin">
          set the admin key (dev-admin locally) to add rates and release a hold.
        </Step>
      </List>
    </Card>
  )
}

function Step({ icon, to, label, children }: { icon: ReactNode; to: string; label: string; children: ReactNode }) {
  return (
    <List.Item icon={<ThemeIcon size={24} radius="xl" variant="light">{icon}</ThemeIcon>}>
      <Anchor component={Link} to={to} fw={600} inherit>{label}</Anchor>: {children}
    </List.Item>
  )
}

function DemoAccounts() {
  const accounts = useAllAccounts()
  const demo = DEMO_ACCOUNTS.map((d) => accounts.data?.find((account) => account.id === d.id)).filter(Boolean)
  return (
    <Card title="Demo accounts">
      {accounts.isPending && <Skeleton h={140} />}
      {accounts.error && <ProblemAlert error={accounts.error} />}
      {accounts.data && demo.length === 0 && (
        <Text size="sm" c="dimmed">
          The demo accounts are created when the backend starts with demo data on (docker compose does this).
        </Text>
      )}
      {demo.length > 0 && (
        <Table verticalSpacing={6}>
          <Table.Tbody>
            {demo.map((account) => account && (
              <Table.Tr key={account.id}>
                <Table.Td>
                  <Anchor component={Link} to={`/accounts/${account.id}`} fw={500}>{account.name}</Anchor>
                </Table.Td>
                <Table.Td ta="right" ff="monospace" style={{ whiteSpace: 'nowrap' }}>
                  {formatMoney(account.balance)}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Card>
  )
}

function IntegrityCard() {
  const { runChecks } = useIntegrityActions()
  const report = runChecks.data
  return (
    <Card title="Integrity" action={
      <Button size="xs" variant="light" leftSection={<IconShieldCheck size={14} />} loading={runChecks.isPending}
        onClick={() => runChecks.mutate()}>
        Run checks
      </Button>
    }>
      {!report && !runChecks.error && (
        <Text size="sm" c="dimmed">
          Re-adds every balance, compares money in wallets with money deposited per currency, and verifies the
          hash chain. Runs every 10 minutes on its own too.
        </Text>
      )}
      {runChecks.error && <ProblemAlert error={runChecks.error} />}
      {report && (
        <Stack gap="xs">
          <Group gap="xs">
            <Badge color={report.status === 'CLEAN' ? 'teal' : 'red'}>{report.status}</Badge>
            <Text size="sm" c="dimmed">{report.checkedTransactions} transactions checked</Text>
          </Group>
          <Table verticalSpacing={4}>
            <Table.Tbody>
              {report.moneySupply?.map((line) => (
                <Table.Tr key={line.currency}>
                  <Table.Td w={28}>
                    {line.matches ? <IconCheck size={14} color="var(--mantine-color-teal-6)" />
                      : <IconX size={14} color="var(--mantine-color-red-6)" />}
                  </Table.Td>
                  <Table.Td>{line.currency}</Table.Td>
                  <Table.Td ta="right" ff="monospace" style={{ whiteSpace: 'nowrap' }}>{formatMoney(line.actual)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Anchor component={Link} to="/integrity" size="sm">Full integrity page</Anchor>
        </Stack>
      )}
    </Card>
  )
}

function Card({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="sm">
        <Text fw={600}>{title}</Text>
        {action}
      </Group>
      {children}
    </Paper>
  )
}
