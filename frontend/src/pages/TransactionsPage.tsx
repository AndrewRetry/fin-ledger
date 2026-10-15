import { Button, Group, List, Paper, Stack, Text, TextInput, Title } from '@mantine/core'
import { IconSearch } from '@tabler/icons-react'
import { type FormEvent, useState } from 'react'
import { useNavigate } from 'react-router'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** Opens a transaction by id. Every history row and posted result links to the same page. */
export function TransactionsPage() {
  const [id, setId] = useState('')
  const navigate = useNavigate()
  const value = id.trim().toLowerCase()
  const valid = UUID.test(value)

  function open(event: FormEvent) {
    event.preventDefault()
    if (valid) {
      navigate(`/transactions/${value}`)
    }
  }

  return (
    <Stack maw={760}>
      <Title order={2}>Transactions</Title>
      <Paper withBorder p="md">
        <form onSubmit={open}>
          <Group align="flex-end">
            <TextInput
              label="Transaction id"
              placeholder="Paste an id, e.g. from an account's history"
              value={id}
              onChange={(event) => setId(event.currentTarget.value)}
              error={value && !valid ? 'A transaction id looks like 8398f93a-24c5-3661-8e9a-33d54273d824' : undefined}
              style={{ flex: 1 }}
              ff="monospace"
            />
            <Button type="submit" disabled={!valid} leftSection={<IconSearch size={16} />}>
              Open
            </Button>
          </Group>
        </form>
      </Paper>
      <Paper withBorder p="md">
        <Text fw={500} mb="xs">On a transaction's page you can</Text>
        <List size="sm" spacing={4}>
          <List.Item>see every posting and the balance each account had afterwards;</List.Item>
          <List.Item>check its hash in your browser against the stored one;</List.Item>
          <List.Item>follow the links between a transaction and its reversal;</List.Item>
          <List.Item>reverse it, and see why the ledger refuses a second reversal.</List.Item>
        </List>
      </Paper>
    </Stack>
  )
}
