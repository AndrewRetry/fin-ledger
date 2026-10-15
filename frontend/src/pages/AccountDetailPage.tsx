import {
  Alert,
  Anchor,
  Button,
  Group,
  Pagination,
  Paper,
  SimpleGrid,
  Skeleton,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core'
import {
  IconArrowDownLeft,
  IconArrowLeft,
  IconArrowUpRight,
  IconArrowsExchange,
  IconInfoCircle,
  IconPlayerTrackNext,
  IconPlus,
  IconMinus,
} from '@tabler/icons-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'

import { useAccount, useAccountHistory } from '../api/queries'
import { KindBadge, TypeBadge } from '../components/Badges'
import { EmptyState } from '../components/EmptyState'
import { IdCell } from '../components/IdCell'
import { ProblemAlert } from '../components/ProblemAlert'
import { formatMoney, groupThousands } from '../lib/money'
import { formatTime } from '../lib/time'

const PAGE_SIZE = 20

const SYSTEM_NOTES: Record<string, string> = {
  FUNDING:
    'Stands for the outside world in this currency. Deposits come from it and withdrawals go back to it, so its ' +
    'balance is minus the net money deposited and never above zero.',
  FX_LIQUIDITY:
    "The house's wallet in this currency. Cross-currency transfers pay into one house wallet and out of another.",
}

export function AccountDetailPage() {
  const { id = '' } = useParams()
  const [page, setPage] = useState(0)
  const account = useAccount(id)
  const history = useAccountHistory(id, page, PAGE_SIZE)

  if (account.error) {
    return (
      <Stack>
        <BackLink />
        <ProblemAlert error={account.error} onRetry={() => account.refetch()} />
      </Stack>
    )
  }

  const data = account.data
  return (
    <Stack>
      <BackLink />

      <Paper withBorder p="md">
        {data ? (
          <Stack gap="xs">
            <Group justify="space-between" align="flex-start">
              <div>
                <Group gap="xs">
                  <Title order={2}>{data.name}</Title>
                  <KindBadge kind={data.kind} />
                </Group>
                <IdCell value={data.id} />
              </div>
              {data.kind === 'CUSTOMER' && <AccountActions id={data.id} />}
              {data.kind === 'FX_LIQUIDITY' && (
                <Button component={Link} to={`/move?tab=deposit&account=${data.id}`} variant="light"
                  leftSection={<IconPlus size={16} />}>
                  Deposit
                </Button>
              )}
            </Group>
            <SimpleGrid cols={{ base: 1, sm: 3 }}>
              <Stat label="Balance" value={formatMoney(data.balance)} large />
              <Stat label="Version (postings so far)" value={String(data.version)} />
              <Stat label="Created" value={formatTime(data.createdAt)} />
            </SimpleGrid>
            {SYSTEM_NOTES[data.kind] && (
              <Alert variant="light" color="gray" icon={<IconInfoCircle size={18} />}>
                {SYSTEM_NOTES[data.kind]}
              </Alert>
            )}
          </Stack>
        ) : (
          <Skeleton h={120} />
        )}
      </Paper>

      <Paper withBorder>
        <Group p="sm" justify="space-between">
          <div>
            <Text fw={600}>History</Text>
            <Text size="sm" c="dimmed">
              Oldest first, in the order the postings happened (account version).
            </Text>
          </div>
          {history.data && history.data.totalPages > 1 && (
            <Button
              variant="subtle"
              size="xs"
              rightSection={<IconPlayerTrackNext size={14} />}
              onClick={() => setPage(history.data.totalPages - 1)}
              disabled={page === history.data.totalPages - 1}
            >
              Latest
            </Button>
          )}
        </Group>

        {history.error && <ProblemAlert error={history.error} onRetry={() => history.refetch()} />}

        <Table.ScrollContainer minWidth={900}>
          <Table striped highlightOnHover verticalSpacing="xs">
            <Table.Thead>
              <Table.Tr>
                <Table.Th ta="right">Version</Table.Th>
                <Table.Th>When</Table.Th>
                <Table.Th>Type</Table.Th>
                <Table.Th ta="right">Amount</Table.Th>
                <Table.Th ta="right">Balance after</Table.Th>
                <Table.Th>Description</Table.Th>
                <Table.Th>Transaction</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {history.isPending &&
                Array.from({ length: 3 }, (_, i) => (
                  <Table.Tr key={i}>
                    <Table.Td colSpan={7}>
                      <Skeleton h={20} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              {history.data?.items.map((item) => {
                const credit = item.direction === 'CREDIT'
                return (
                  <Table.Tr key={item.accountVersion}>
                    <Table.Td ta="right">{item.accountVersion}</Table.Td>
                    <Table.Td>
                      <Text size="sm" style={{ whiteSpace: 'nowrap' }}>
                        {formatTime(item.createdAt)}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <TypeBadge type={item.type} />
                    </Table.Td>
                    <Table.Td ta="right">
                      <Group gap={4} justify="flex-end" wrap="nowrap" c={credit ? 'teal' : 'red'}>
                        {credit ? <IconArrowDownLeft size={14} /> : <IconArrowUpRight size={14} />}
                        <Text size="sm" ff="monospace" c="inherit">
                          {credit ? '+' : '−'}
                          {groupThousands(item.amount.display)}
                        </Text>
                      </Group>
                    </Table.Td>
                    <Table.Td ta="right" ff="monospace">
                      {groupThousands(item.balanceAfter.display)}
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" lineClamp={1} c={item.description ? undefined : 'dimmed'}>
                        {item.description ?? '—'}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <IdCell value={item.transactionId} to={`/transactions/${item.transactionId}`} />
                    </Table.Td>
                  </Table.Tr>
                )
              })}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>

        {history.data && history.data.items.length === 0 && (
          <EmptyState title="No transactions yet" hint="A deposit is the first thing that happens to a new account." />
        )}

        {history.data && history.data.totalPages > 1 && (
          <Group p="sm" justify="space-between">
            <Text size="sm" c="dimmed">
              {history.data.totalItems} postings
            </Text>
            <Pagination
              total={history.data.totalPages}
              value={page + 1}
              onChange={(value) => setPage(value - 1)}
              size="sm"
            />
          </Group>
        )}
      </Paper>
    </Stack>
  )
}

function BackLink() {
  return (
    <Anchor component={Link} to="/accounts" size="sm">
      <Group gap={4}>
        <IconArrowLeft size={14} />
        All accounts
      </Group>
    </Anchor>
  )
}

/** Opens the Move-money forms with this account already filled in. */
function AccountActions({ id }: { id: string }) {
  return (
    <Group gap="xs">
      <Button component={Link} to={`/move?tab=deposit&account=${id}`} variant="light"
        leftSection={<IconPlus size={16} />}>
        Deposit
      </Button>
      <Button component={Link} to={`/move?tab=withdraw&account=${id}`} variant="light"
        leftSection={<IconMinus size={16} />}>
        Withdraw
      </Button>
      <Button component={Link} to={`/move?tab=transfer&account=${id}`}
        leftSection={<IconArrowsExchange size={16} />}>
        Transfer
      </Button>
    </Group>
  )
}

function Stat({ label, value, large }: { label: string; value: string; large?: boolean }) {
  return (
    <div>
      <Text size="sm" c="dimmed">
        {label}
      </Text>
      <Text fz={large ? 26 : 'md'} fw={large ? 600 : 400} ff={large ? 'monospace' : undefined}>
        {value}
      </Text>
    </div>
  )
}
