import { Anchor, Badge, Group, Paper, SimpleGrid, Stack, Table, Text, Tooltip } from '@mantine/core'
import { IconArrowDownLeft, IconArrowUpRight, IconRepeat } from '@tabler/icons-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'

import type { Transaction } from '../api/money'
import { useAllAccounts } from '../api/queries'
import { shortId } from '../lib/ids'
import { groupThousands } from '../lib/money'
import { formatTime } from '../lib/time'
import { TypeBadge } from './Badges'
import { IdCell } from './IdCell'

/**
 * One transaction, shown the same way everywhere: what it is, its postings with the balance each account had
 * afterwards, and where it sits in the hash chain. Values that are null are shown as a dash.
 */
export function TransactionCard({ tx, replayed, reversedById, actions, footer }: {
  tx: Transaction
  replayed?: boolean
  reversedById?: string | null
  actions?: ReactNode
  footer?: ReactNode
}) {
  const accounts = useAllAccounts()
  const nameOf = (id: string) => accounts.data?.find((account) => account.id === id)?.name

  return (
    <Paper withBorder p="md">
      <Stack gap="sm">
        <Group justify="space-between" align="flex-start">
          <Group gap="xs">
            <TypeBadge type={tx.type} />
            <Badge color="teal" variant="dot">
              {tx.status}
            </Badge>
            {replayed && (
              <Badge color="gray" variant="light" leftSection={<IconRepeat size={12} />}>
                Idempotent replay
              </Badge>
            )}
            {reversedById && (
              <Badge color="orange" variant="light">
                Reversed
              </Badge>
            )}
          </Group>
          {actions}
        </Group>

        <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="xs" verticalSpacing="xs">
          <Field label="Transaction id"><IdCell value={tx.id} to={`/transactions/${tx.id}`} /></Field>
          <Field label="Created">{formatTime(tx.createdAt)}</Field>
          <Field label="Description">{tx.description ?? <Dash />}</Field>
          <Field label="Idempotency-Key"><Text ff="monospace" size="sm" truncate>{tx.idempotencyKey}</Text></Field>
          <Field label="External reference">{tx.externalRef ?? <Dash />}</Field>
          {tx.reversesId && (
            <Field label="Reverses"><IdCell value={tx.reversesId} to={`/transactions/${tx.reversesId}`} /></Field>
          )}
          {reversedById && (
            <Field label="Reversed by"><IdCell value={reversedById} to={`/transactions/${reversedById}`} /></Field>
          )}
          {tx.fx && (
            <Field label="Exchange rate used">
              1 {tx.fx.base} = {tx.fx.rate} {tx.fx.quote} ({tx.fx.source})
            </Field>
          )}
        </SimpleGrid>

        <Table.ScrollContainer minWidth={560}>
          <Table verticalSpacing={6} withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Account</Table.Th>
                <Table.Th ta="right">Amount</Table.Th>
                <Table.Th ta="right">Balance after</Table.Th>
                <Table.Th ta="right">Version</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {tx.postings.map((posting) => {
                const credit = posting.direction === 'CREDIT'
                return (
                  <Table.Tr key={posting.accountId}>
                    <Table.Td>
                      <Tooltip label={posting.accountId} withArrow openDelay={300}>
                        <Anchor component={Link} to={`/accounts/${posting.accountId}`} size="sm"
                          style={{ whiteSpace: 'nowrap' }}>
                          {nameOf(posting.accountId) ?? shortId(posting.accountId)}
                        </Anchor>
                      </Tooltip>
                    </Table.Td>
                    <Table.Td ta="right">
                      <Group gap={4} justify="flex-end" wrap="nowrap" c={credit ? 'teal' : 'red'}>
                        {credit ? <IconArrowDownLeft size={14} /> : <IconArrowUpRight size={14} />}
                        <Text size="sm" ff="monospace" c="inherit" style={{ whiteSpace: 'nowrap' }}>
                          {credit ? '+' : '−'}{groupThousands(posting.amount.display)} {posting.amount.currency}
                        </Text>
                      </Group>
                    </Table.Td>
                    <Table.Td ta="right" ff="monospace" style={{ whiteSpace: 'nowrap' }}>
                      {groupThousands(posting.balanceAfter.display)}
                    </Table.Td>
                    <Table.Td ta="right">{posting.accountVersion}</Table.Td>
                  </Table.Tr>
                )
              })}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>

        <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="xs">
          <Field label="Chain index">{tx.chainIndex}</Field>
          <Field label="Previous hash"><IdCell value={tx.prevHash} /></Field>
          <Field label="Transaction hash"><IdCell value={tx.txHash} /></Field>
        </SimpleGrid>

        {footer}
      </Stack>
    </Paper>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div style={{ minWidth: 0 }}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" component="div">
        {children}
      </Text>
    </div>
  )
}

function Dash() {
  return <Text span c="dimmed">—</Text>
}
