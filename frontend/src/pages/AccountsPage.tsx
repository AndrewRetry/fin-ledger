import { Anchor, Button, Group, Pagination, Paper, Select, Skeleton, Stack, Table, Text, TextInput, Title } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { IconPlus, IconSearch } from '@tabler/icons-react'
import { useState } from 'react'
import { Link } from 'react-router'

import { useAccounts } from '../api/queries'
import { KindBadge } from '../components/Badges'
import { CreateAccountModal } from '../components/CreateAccountModal'
import { EmptyState } from '../components/EmptyState'
import { IdCell } from '../components/IdCell'
import { ProblemAlert } from '../components/ProblemAlert'
import { formatMoney } from '../lib/money'
import { formatTime } from '../lib/time'

export function AccountsPage() {
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [search, setSearch] = useState('')
  const [creating, { open, close }] = useDisclosure()
  const accounts = useAccounts(page, size)

  const query = search.trim().toLowerCase()
  const rows = (accounts.data?.items ?? []).filter(
    (account) => !query || account.name.toLowerCase().includes(query) || account.id.includes(query),
  )

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Accounts</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={open}>
          New account
        </Button>
      </Group>

      <Text c="dimmed" size="sm">
        Customer wallets, plus the house's FX liquidity wallets and one FUNDING account per currency that stands for
        the outside world. Oldest first.
      </Text>

      {accounts.error && <ProblemAlert error={accounts.error} onRetry={() => accounts.refetch()} />}

      <Paper withBorder>
        <Group p="sm" justify="space-between">
          <TextInput
            placeholder="Search this page by name or id"
            leftSection={<IconSearch size={16} />}
            value={search}
            onChange={(event) => setSearch(event.currentTarget.value)}
            w={320}
          />
          <Select
            aria-label="Rows per page"
            data={['20', '50', '100']}
            value={String(size)}
            onChange={(value) => {
              setSize(Number(value))
              setPage(0)
            }}
            allowDeselect={false}
            w={90}
          />
        </Group>

        <Table.ScrollContainer minWidth={860}>
          <Table striped highlightOnHover verticalSpacing="xs">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Name</Table.Th>
                <Table.Th>Kind</Table.Th>
                <Table.Th>Currency</Table.Th>
                <Table.Th ta="right">Balance</Table.Th>
                <Table.Th ta="right">Version</Table.Th>
                <Table.Th>Created</Table.Th>
                <Table.Th>Id</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {accounts.isPending &&
                Array.from({ length: 5 }, (_, i) => (
                  <Table.Tr key={i}>
                    <Table.Td colSpan={7}>
                      <Skeleton h={20} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              {rows.map((account) => (
                <Table.Tr key={account.id}>
                  <Table.Td>
                    <Anchor component={Link} to={`/accounts/${account.id}`} fw={500} style={{ whiteSpace: 'nowrap' }}>
                      {account.name}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>
                    <KindBadge kind={account.kind} />
                  </Table.Td>
                  <Table.Td>{account.currency}</Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {formatMoney(account.balance)}
                  </Table.Td>
                  <Table.Td ta="right">{account.version}</Table.Td>
                  <Table.Td>
                    <Text size="sm" style={{ whiteSpace: 'nowrap' }}>
                      {formatTime(account.createdAt)}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <IdCell value={account.id} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>

        {accounts.data && rows.length === 0 && (
          <EmptyState title={query ? 'No account on this page matches' : 'No accounts yet'} />
        )}

        {accounts.data && (
          <Group p="sm" justify="space-between">
            <Text size="sm" c="dimmed">
              {accounts.data.totalItems} accounts
            </Text>
            <Pagination
              total={accounts.data.totalPages}
              value={page + 1}
              onChange={(value) => setPage(value - 1)}
              size="sm"
            />
          </Group>
        )}
      </Paper>

      <CreateAccountModal opened={creating} onClose={close} />
    </Stack>
  )
}
