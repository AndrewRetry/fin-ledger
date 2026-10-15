import { ActionIcon, CopyButton, Group, Paper, SimpleGrid, Skeleton, Stack, Text, Title, Tooltip } from '@mantine/core'
import { IconCheck, IconCopy } from '@tabler/icons-react'

import { useLedgerHead } from '../api/queries'
import { ProblemAlert } from '../components/ProblemAlert'

export function OverviewPage() {
  const head = useLedgerHead()

  return (
    <Stack>
      <Title order={2}>Overview</Title>
      {head.error && <ProblemAlert error={head.error} onRetry={() => head.refetch()} />}
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Paper withBorder p="md">
          <Text size="sm" c="dimmed">
            Hash chain length
          </Text>
          {head.data ? (
            <Text fz={32} fw={600}>
              {head.data.chainLength}
            </Text>
          ) : (
            <Skeleton h={40} w={120} mt={4} />
          )}
          <Text size="xs" c="dimmed">
            Every committed transaction is one link.
          </Text>
        </Paper>
        <Paper withBorder p="md">
          <Text size="sm" c="dimmed">
            Head hash
          </Text>
          {head.data ? (
            <Group gap="xs" wrap="nowrap" mt={4}>
              <Text ff="monospace" size="sm" style={{ wordBreak: 'break-all' }}>
                {head.data.headHash}
              </Text>
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
          <Text size="xs" c="dimmed">
            Record it somewhere outside the system: any later edit to history changes it.
          </Text>
        </Paper>
      </SimpleGrid>
    </Stack>
  )
}
