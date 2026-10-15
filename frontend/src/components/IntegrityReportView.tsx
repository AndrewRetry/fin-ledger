import { Anchor, Badge, Group, Paper, ScrollArea, SimpleGrid, Stack, Table, Text, ThemeIcon, Tooltip } from '@mantine/core'
import { IconCheck, IconX } from '@tabler/icons-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'

import { type IntegrityReport, useAllAccounts } from '../api/queries'
import { shortId } from '../lib/ids'
import { formatMoney } from '../lib/money'
import { formatTime } from '../lib/time'
import { IdCell } from './IdCell'

/**
 * One report for both kinds of run. Sections that a run doesn't have are left out: a stored run keeps only its
 * discrepancies, so money supply, chain and account lines exist only in the answer to a live run.
 */
export function IntegrityReportView({ report }: { report: IntegrityReport }) {
  const clean = report.status === 'CLEAN'
  const live = report.kind === 'INTEGRITY' ? report.chain !== null : report.accounts !== null

  return (
    <Stack>
      <Paper withBorder p="md">
        <Stack gap="sm">
          <Group gap="xs">
            <Badge variant="light" color={report.kind === 'INTEGRITY' ? 'indigo' : 'violet'} radius="sm">
              {report.kind === 'INTEGRITY' ? 'Integrity checks' : `Month-end ${report.period}`}
            </Badge>
            <Badge color={clean ? 'teal' : 'red'} leftSection={clean ? <IconCheck size={12} /> : <IconX size={12} />}>
              {report.status}
            </Badge>
            {report.provisional && <Badge color="orange" variant="light">Provisional: month not over</Badge>}
            {report.integrityHold && <Badge color="orange">Integrity hold on</Badge>}
          </Group>
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }} spacing="xs">
            <Field label="Run id"><IdCell value={report.runId} /></Field>
            <Field label="Finished">{formatTime(report.finishedAt)}</Field>
            <Field label="Checked">{report.checkedAccounts} accounts · {report.checkedTransactions} transactions</Field>
            {report.kind === 'MONTH_END' && (
              <Field label="Snapshots frozen by this run">{report.snapshotsWritten}</Field>
            )}
          </SimpleGrid>
          {!live && (
            <Text size="sm" c="dimmed">
              A stored run keeps its result and discrepancies; the totals below appear only when a run happens.
            </Text>
          )}
        </Stack>
      </Paper>

      <SimpleGrid cols={{ base: 1, lg: report.moneySupply ? 2 : 1 }} spacing="md">
        <Section title="Checks">
          <Table verticalSpacing={6}>
            <Table.Tbody>
              {report.checks.map((check) => (
                <Table.Tr key={check.code}>
                  <Table.Td w={32}><Verdict ok={check.discrepancies === 0} /></Table.Td>
                  <Table.Td ff="monospace" fz="sm">{check.code}</Table.Td>
                  <Table.Td ta="right">
                    <Text size="sm" c={check.discrepancies === 0 ? 'dimmed' : 'red'}>
                      {check.discrepancies === 0 ? 'none found' : `${check.discrepancies} found`}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Section>

        {report.moneySupply && (
          <Section title="Money supply" hint="Money held in wallets vs net money deposited from outside">
            <Table verticalSpacing={6}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th w={32} />
                  <Table.Th>Currency</Table.Th>
                  <Table.Th ta="right">Deposited (expected)</Table.Th>
                  <Table.Th ta="right">In wallets (actual)</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {report.moneySupply.map((line) => (
                  <Table.Tr key={line.currency}>
                    <Table.Td><Verdict ok={line.matches} /></Table.Td>
                    <Table.Td>{line.currency}</Table.Td>
                    <Table.Td ta="right" ff="monospace" style={{ whiteSpace: 'nowrap' }}>{formatMoney(line.expected)}</Table.Td>
                    <Table.Td ta="right" ff="monospace" style={{ whiteSpace: 'nowrap' }}>{formatMoney(line.actual)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Section>
        )}
      </SimpleGrid>

      {report.chain && (
        <Section title="Hash chain">
          <Group gap="xl" p="sm">
            <Group gap="xs"><Verdict ok={report.chain.valid} /><Text size="sm">{report.chain.valid ? 'Every link verified' : 'Broken'}</Text></Group>
            <Field label="Length">{report.chain.length}</Field>
            <Field label="Head hash"><IdCell value={report.chain.headHash} /></Field>
          </Group>
        </Section>
      )}

      {report.discrepancies.length > 0 && <Discrepancies report={report} />}
      {report.accounts && <AccountLines report={report} />}
    </Stack>
  )
}

function Discrepancies({ report }: { report: IntegrityReport }) {
  const nameOf = useAccountNames()
  return (
    <Section title={`Discrepancies (${report.discrepancies.length})`}
      hint="Expected and actual are integer minor units of the currency">
      <Table.ScrollContainer minWidth={900}>
        <Table verticalSpacing={6} striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Check</Table.Th>
              <Table.Th>Account</Table.Th>
              <Table.Th>Transaction</Table.Th>
              <Table.Th ta="right">Expected</Table.Th>
              <Table.Th ta="right">Actual</Table.Th>
              <Table.Th>Detail</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {report.discrepancies.map((d, i) => (
              <Table.Tr key={i}>
                <Table.Td ff="monospace" fz="xs" c="red">{d.checkCode}</Table.Td>
                <Table.Td>{d.accountId ? <AccountLink id={d.accountId} name={nameOf(d.accountId)} /> : <Dash />}</Table.Td>
                <Table.Td>{d.transactionId ? <IdCell value={d.transactionId} to={`/transactions/${d.transactionId}`} /> : <Dash />}</Table.Td>
                <Table.Td ta="right" ff="monospace">{d.expected ?? '—'}</Table.Td>
                <Table.Td ta="right" ff="monospace">{d.actual ?? '—'}</Table.Td>
                <Table.Td><Text size="sm">{d.currency ? `${d.currency}: ` : ''}{d.detail}</Text></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
    </Section>
  )
}

/** Month-end, per account: opening + credits − debits should land on the recorded closing. */
function AccountLines({ report }: { report: IntegrityReport }) {
  const nameOf = useAccountNames()
  return (
    <Section title={`Accounts (${report.accounts.length})`} hint="Opening + credits − debits = expected closing">
      <ScrollArea.Autosize mah={480} type="auto">
        <Table.ScrollContainer minWidth={900}>
          <Table verticalSpacing={6} striped stickyHeader>
            <Table.Thead>
              <Table.Tr>
                <Table.Th w={32} />
                <Table.Th>Account</Table.Th>
                <Table.Th ta="right">Opening</Table.Th>
                <Table.Th ta="right">Credits</Table.Th>
                <Table.Th ta="right">Debits</Table.Th>
                <Table.Th ta="right">Expected closing</Table.Th>
                <Table.Th ta="right">Recorded closing</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {report.accounts.map((line) => (
                <Table.Tr key={line.accountId}>
                  <Table.Td><Verdict ok={line.matches} /></Table.Td>
                  <Table.Td><AccountLink id={line.accountId} name={nameOf(line.accountId)} /></Table.Td>
                  {[line.opening, line.credits, line.debits, line.expectedClosing, line.actualClosing].map((money, i) => (
                    <Table.Td key={i} ta="right" ff="monospace" style={{ whiteSpace: 'nowrap' }}>{formatMoney(money)}</Table.Td>
                  ))}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      </ScrollArea.Autosize>
    </Section>
  )
}

function useAccountNames() {
  const accounts = useAllAccounts()
  return (id: string) => accounts.data?.find((account) => account.id === id)?.name
}

function AccountLink({ id, name }: { id: string; name?: string }) {
  return (
    <Tooltip label={id} withArrow openDelay={300}>
      <Anchor component={Link} to={`/accounts/${id}`} size="sm" style={{ whiteSpace: 'nowrap' }}>
        {name ?? shortId(id)}
      </Anchor>
    </Tooltip>
  )
}

function Verdict({ ok }: { ok: boolean }) {
  return (
    <ThemeIcon size="sm" radius="xl" variant="light" color={ok ? 'teal' : 'red'} aria-label={ok ? 'ok' : 'problem'}>
      {ok ? <IconCheck size={12} /> : <IconX size={12} />}
    </ThemeIcon>
  )
}

function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <Paper withBorder>
      <Stack gap={0} p="sm" pb={4}>
        <Text fw={600}>{title}</Text>
        {hint && <Text size="xs" c="dimmed">{hint}</Text>}
      </Stack>
      {children}
    </Paper>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div style={{ minWidth: 0 }}>
      <Text size="xs" c="dimmed">{label}</Text>
      <Text size="sm" component="div">{children}</Text>
    </div>
  )
}

function Dash() {
  return <Text span c="dimmed">—</Text>
}
