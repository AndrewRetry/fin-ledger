import { Anchor, Button, Group, Skeleton, Stack, Title } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { IconArrowBackUp, IconArrowLeft } from '@tabler/icons-react'
import { Link, useParams } from 'react-router'

import { useTransaction } from '../api/queries'
import { HashCheck } from '../components/HashCheck'
import { ProblemAlert } from '../components/ProblemAlert'
import { ReverseModal } from '../components/ReverseModal'
import { TransactionCard } from '../components/TransactionCard'

export function TransactionDetailPage() {
  const { id = '' } = useParams()
  const transaction = useTransaction(id)
  const [reversing, { open, close }] = useDisclosure()

  return (
    <Stack>
      <Anchor component={Link} to="/transactions" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          Find a transaction
        </Group>
      </Anchor>
      <Title order={2}>Transaction</Title>

      {transaction.error && <ProblemAlert error={transaction.error} onRetry={() => transaction.refetch()} />}
      {transaction.isPending && <Skeleton h={320} />}
      {transaction.data && (
        <>
          <TransactionCard
            tx={transaction.data}
            reversedById={transaction.data.reversedById}
            actions={
              <Button size="xs" color="orange" variant="light" leftSection={<IconArrowBackUp size={14} />}
                onClick={open}>
                Reverse
              </Button>
            }
            footer={<HashCheck tx={transaction.data} />}
          />
          <ReverseModal transactionId={transaction.data.id} opened={reversing} onClose={close} />
        </>
      )}
    </Stack>
  )
}
