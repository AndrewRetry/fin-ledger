import { Autocomplete, Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconCheck } from '@tabler/icons-react'
import { type FormEvent, useState } from 'react'
import { useNavigate } from 'react-router'

import { ApiProblem } from '../api/problem'
import { useCreateAccount } from '../api/queries'
import { ProblemAlert } from './ProblemAlert'

/** Seeded by the backend's V1 migration; free text is allowed so CURRENCY_NOT_SUPPORTED can be seen too. */
const CURRENCIES = ['SGD', 'USD', 'EUR', 'JPY']

export function CreateAccountModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const [name, setName] = useState('')
  const [currency, setCurrency] = useState('SGD')
  const create = useCreateAccount()
  const navigate = useNavigate()
  const problem = create.error instanceof ApiProblem ? create.error : null

  function close() {
    create.reset()
    setName('')
    onClose()
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    create.mutate(
      { name, currency: currency.trim().toUpperCase() },
      {
        onSuccess: (account) => {
          notifications.show({
            color: 'teal',
            icon: <IconCheck size={16} />,
            title: 'Account created',
            message: `${account.name} (${account.currency})`,
          })
          close()
          navigate(`/accounts/${account.id}`)
        },
      },
    )
  }

  return (
    <Modal opened={opened} onClose={close} title="New customer account" centered>
      <form onSubmit={submit}>
        <Stack>
          <Text size="sm" c="dimmed">
            Accounts start empty and hold one currency. Add money with a deposit.
          </Text>
          <TextInput
            label="Name"
            placeholder="Dana"
            value={name}
            onChange={(event) => setName(event.currentTarget.value)}
            error={problem?.fieldError('name')}
            data-autofocus
          />
          <Autocomplete
            label="Currency"
            data={CURRENCIES}
            value={currency}
            onChange={setCurrency}
            error={problem?.fieldError('currency')}
          />
          {problem && problem.errors.length === 0 && <ProblemAlert error={problem} />}
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              Cancel
            </Button>
            <Button type="submit" loading={create.isPending}>
              Create account
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  )
}
