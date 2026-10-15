import { Alert, Button, Group, Paper, SimpleGrid, Stack, Tabs, Text, TextInput, Title } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
  IconArrowsExchange,
  IconExternalLink,
  IconInfoCircle,
  IconMinus,
  IconPlus,
  IconRefresh,
  IconRepeat,
  IconWand,
} from '@tabler/icons-react'
import { type FormEvent, type ReactNode, useState } from 'react'
import { Link, useSearchParams } from 'react-router'

import { type MoneyRequest, useMoneyRequest } from '../api/money'
import { ApiProblem } from '../api/problem'
import { useAccountById, useFxRates } from '../api/queries'
import { AccountSelect } from '../components/AccountSelect'
import { IdempotencyField } from '../components/IdempotencyField'
import { MoneyInput } from '../components/MoneyInput'
import { ProblemAlert } from '../components/ProblemAlert'
import { TransactionCard } from '../components/TransactionCard'
import { convertMinor } from '../lib/fxPreview'
import { decimalsOf, fromMinor, groupThousands, minorOrNull } from '../lib/money'
import { useIdempotencyKey } from '../lib/useIdempotencyKey'

type Tab = 'transfer' | 'deposit' | 'withdraw'

export function MoveMoneyPage() {
  const [params, setParams] = useSearchParams()
  const tab = (params.get('tab') as Tab | null) ?? 'transfer'
  const account = params.get('account')

  return (
    <Stack>
      <Title order={2}>Move money</Title>
      <Text c="dimmed" size="sm">
        Amounts are typed in the account's currency and sent as integer minor units. Every request carries an
        Idempotency-Key, so sending it twice can never move money twice.
      </Text>
      <Tabs value={tab} onChange={(value) => setParams({ tab: value ?? 'transfer' })} keepMounted>
        <Tabs.List mb="md">
          <Tabs.Tab value="transfer" leftSection={<IconArrowsExchange size={16} />}>Transfer</Tabs.Tab>
          <Tabs.Tab value="deposit" leftSection={<IconPlus size={16} />}>Deposit</Tabs.Tab>
          <Tabs.Tab value="withdraw" leftSection={<IconMinus size={16} />}>Withdraw</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="transfer">
          <TransferForm initialSource={tab === 'transfer' ? account : null} />
        </Tabs.Panel>
        <Tabs.Panel value="deposit">
          <MovementForm kind="deposit" initialAccount={tab === 'deposit' ? account : null} />
        </Tabs.Panel>
        <Tabs.Panel value="withdraw">
          <MovementForm kind="withdraw" initialAccount={tab === 'withdraw' ? account : null} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  )
}

/**
 * Shared by every form: the key, the request in flight, and the last request sent, so it can be sent again
 * with the same key to show an idempotent replay.
 */
function useSubmission() {
  const idempotency = useIdempotencyKey()
  const mutation = useMoneyRequest()
  const [lastRequest, setLastRequest] = useState<MoneyRequest | null>(null)
  const problem = mutation.error instanceof ApiProblem ? mutation.error : null

  function submit(request: MoneyRequest) {
    setLastRequest(request)
    mutation.mutate(
      { request, key: idempotency.key },
      {
        onSuccess: ({ replayed }) =>
          notifications.show({
            color: replayed ? 'gray' : 'teal',
            title: replayed ? 'Replayed, nothing moved again' : 'Posted',
            message: replayed
              ? 'The same key and body returned the original transaction.'
              : 'The transaction is committed and linked into the hash chain.',
          }),
      },
    )
  }

  function sendAgain() {
    if (lastRequest) {
      submit(lastRequest)
    }
  }

  function startNew() {
    mutation.reset()
    idempotency.renew()
  }

  return { idempotency, mutation, problem, submit, sendAgain, startNew }
}

type Submission = ReturnType<typeof useSubmission>

function TransferForm({ initialSource }: { initialSource: string | null }) {
  const [sourceId, setSourceId] = useState<string | null>(initialSource)
  const [destinationId, setDestinationId] = useState<string | null>(null)
  const [amount, setAmount] = useState('')
  const [currency, setCurrency] = useState('')
  const [description, setDescription] = useState('')
  const submission = useSubmission()
  const source = useAccountById(sourceId)
  const destination = useAccountById(destinationId)
  const decimals = source ? decimalsOf(source.balance) : undefined
  const minor = minorOrNull(amount, decimals)
  const problem = submission.problem

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!sourceId || !destinationId || minor === null) {
      return
    }
    submission.submit({
      kind: 'transfer',
      body: {
        sourceAccountId: sourceId,
        destinationAccountId: destinationId,
        amount: minor,
        currency: currency.trim() || undefined,
        description: description.trim() || undefined,
      },
    })
  }

  return (
    <FormLayout submission={submission}>
      <form onSubmit={submit}>
        <Stack>
          <AccountSelect label="From" value={sourceId} onChange={setSourceId}
            error={problem?.fieldError('sourceAccountId')} />
          <AccountSelect label="To" value={destinationId} onChange={setDestinationId}
            error={problem?.fieldError('destinationAccountId')} />
          <MoneyInput label="Amount" value={amount} onChange={setAmount} currency={source?.currency}
            decimals={decimals} error={problem?.fieldError('amount')} />
          {source && destination && source.currency !== destination.currency && (
            <FxPreview from={source.currency} to={destination.currency} minor={minor}
              fromDecimals={decimalsOf(source.balance)} toDecimals={decimalsOf(destination.balance)} />
          )}
          <TextInput label="Description" placeholder="Lunch split" value={description}
            onChange={(event) => setDescription(event.currentTarget.value)} error={problem?.fieldError('description')} />
          <TextInput label="Currency of the amount (optional)"
            description="If sent, it must be the source account's currency; try another to see CURRENCY_MISMATCH."
            placeholder={source?.currency ?? 'SGD'} value={currency}
            onChange={(event) => setCurrency(event.currentTarget.value.toUpperCase())}
            error={problem?.fieldError('currency')} />
          <IdempotencyField value={submission.idempotency.key} onChange={submission.idempotency.setKey}
            onRenew={submission.idempotency.renew} />
          <Button type="submit" loading={submission.mutation.isPending}
            disabled={!sourceId || !destinationId || minor === null} leftSection={<IconArrowsExchange size={16} />}>
            Transfer
          </Button>
        </Stack>
      </form>
    </FormLayout>
  )
}

/** What the recipient should get, worked out like the backend does; the server's answer wins. */
function FxPreview({ from, to, minor, fromDecimals, toDecimals }: {
  from: string
  to: string
  minor: number | null
  fromDecimals: number
  toDecimals: number
}) {
  const rates = useFxRates()
  const rate = rates.data?.find((r) => r.base === from && r.quote === to)
  if (!rate) {
    return null
  }
  const received = minor === null ? null : convertMinor(minor, rate.rate, fromDecimals, toDecimals)
  return (
    <Alert variant="light" color="violet" icon={<IconInfoCircle size={18} />} title="Cross-currency transfer">
      <Text size="sm">
        Goes through the house's FX wallets at 1 {from} = {rate.rate} {to} ({rate.source}), rounded down.
        {received !== null && (
          <> The recipient gets about <b>{groupThousands(fromMinor(received, toDecimals))} {to}</b>.</>
        )}
      </Text>
      <Text size="xs" c="dimmed" mt={4}>
        An estimate made in the browser; the backend decides the final amount.
      </Text>
    </Alert>
  )
}

function MovementForm({ kind, initialAccount }: { kind: 'deposit' | 'withdraw'; initialAccount: string | null }) {
  const [accountId, setAccountId] = useState<string | null>(initialAccount)
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  const [externalRef, setExternalRef] = useState('')
  const submission = useSubmission()
  const account = useAccountById(accountId)
  const decimals = account ? decimalsOf(account.balance) : undefined
  const minor = minorOrNull(amount, decimals)
  const problem = submission.problem
  const verb = kind === 'deposit' ? 'Deposit' : 'Withdraw'

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!accountId || minor === null) {
      return
    }
    submission.submit({
      kind,
      body: {
        accountId,
        amount: minor,
        description: description.trim() || undefined,
        externalRef: externalRef.trim() || undefined,
      },
    })
  }

  return (
    <FormLayout submission={submission}>
      <form onSubmit={submit}>
        <Stack>
          <Text size="sm" c="dimmed">
            {kind === 'deposit'
              ? 'Money comes in from the outside world (the FUNDING account of the currency).'
              : 'Money goes back out to the outside world (the FUNDING account of the currency).'}
          </Text>
          <AccountSelect label="Account" value={accountId} onChange={setAccountId}
            error={problem?.fieldError('accountId')} />
          <MoneyInput label="Amount" value={amount} onChange={setAmount} currency={account?.currency}
            decimals={decimals} error={problem?.fieldError('amount')} />
          <TextInput label="Description" placeholder={kind === 'deposit' ? 'Top-up' : 'Cash out'}
            value={description} onChange={(event) => setDescription(event.currentTarget.value)}
            error={problem?.fieldError('description')} />
          <TextInput label="External reference (optional)"
            description="The outside system's id for this movement, e.g. a Fireblocks transaction id. Booked once."
            placeholder="fb-tx-1001" value={externalRef}
            onChange={(event) => setExternalRef(event.currentTarget.value)}
            error={problem?.fieldError('externalRef')}
            rightSection={
              <Button variant="subtle" size="compact-xs" leftSection={<IconWand size={12} />}
                onClick={() => setExternalRef(`fb-tx-${crypto.randomUUID().slice(0, 8)}`)}>
                Example
              </Button>
            }
            rightSectionWidth={92} />
          <IdempotencyField value={submission.idempotency.key} onChange={submission.idempotency.setKey}
            onRenew={submission.idempotency.renew} />
          <Button type="submit" loading={submission.mutation.isPending} disabled={!accountId || minor === null}
            leftSection={kind === 'deposit' ? <IconPlus size={16} /> : <IconMinus size={16} />}>
            {verb}
          </Button>
        </Stack>
      </form>
    </FormLayout>
  )
}

/** The form on the left; the backend's answer (or why it refused) on the right. */
function FormLayout({ submission, children }: { submission: Submission; children: ReactNode }) {
  const { mutation, problem } = submission
  const posted = mutation.data
  return (
    <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="md">
      <Paper withBorder p="md">{children}</Paper>
      <Stack>
        {!posted && !mutation.error && (
          <Paper withBorder p="md">
            <Text c="dimmed" size="sm">The backend's answer appears here: the stored transaction, or why it was refused.</Text>
          </Paper>
        )}
        {mutation.error && (
          <>
            <ProblemAlert error={mutation.error} />
            {problem && problem.errors.length > 0 && (
              <Text size="sm" c="dimmed">The fields the backend rejected are marked in the form.</Text>
            )}
          </>
        )}
        {posted && !mutation.error && (
          <TransactionCard
            tx={posted.tx}
            replayed={posted.replayed}
            actions={
              <Group gap="xs">
                <Button size="xs" variant="light" leftSection={<IconRepeat size={14} />}
                  loading={mutation.isPending} onClick={submission.sendAgain}>
                  Send again (same key)
                </Button>
                <Button size="xs" variant="default" leftSection={<IconRefresh size={14} />}
                  onClick={submission.startNew}>
                  New request
                </Button>
                <Button size="xs" variant="subtle" component={Link} to={`/transactions/${posted.tx.id}`}
                  rightSection={<IconExternalLink size={14} />}>
                  Open
                </Button>
              </Group>
            }
          />
        )}
      </Stack>
    </SimpleGrid>
  )
}
