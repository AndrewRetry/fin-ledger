import { ActionIcon, Alert, Badge, Button, CopyButton, Group, Text, Tooltip } from '@mantine/core'
import { IconAlertTriangle, IconCheck, IconCopy, IconPlugConnectedX, IconRefresh } from '@tabler/icons-react'

import { ApiProblem } from '../api/problem'
import { shortId } from '../lib/ids'

/** Shows any failed request the same way: what went wrong, the stable code, and the request id to quote. */
export function ProblemAlert({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const problem = error instanceof ApiProblem ? error : null
  const offline = problem?.code === 'BACKEND_UNREACHABLE'
  return (
    <Alert
      color={offline ? 'orange' : 'red'}
      icon={offline ? <IconPlugConnectedX size={18} /> : <IconAlertTriangle size={18} />}
      title={problem?.title ?? 'Something went wrong'}
    >
      <Text size="sm">{problem?.detail ?? String(error)}</Text>
      <Group gap="xs" mt="xs">
        {problem && !offline && (
          <Badge variant="light" color="red" radius="sm">
            {problem.status} {problem.code}
          </Badge>
        )}
        {problem?.requestId && (
          <Group gap={4} wrap="nowrap">
            <Tooltip label={problem.requestId} withArrow>
              <Text size="xs" c="dimmed" ff="monospace">
                request {shortId(problem.requestId)}
              </Text>
            </Tooltip>
            <CopyButton value={problem.requestId}>
              {({ copied, copy }) => (
                <Tooltip label={copied ? 'Copied' : 'Copy request id'} withArrow>
                  <ActionIcon size="sm" variant="subtle" color="gray" onClick={copy} aria-label="Copy request id">
                    {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
                  </ActionIcon>
                </Tooltip>
              )}
            </CopyButton>
          </Group>
        )}
        {onRetry && (
          <Button size="compact-xs" variant="light" leftSection={<IconRefresh size={14} />} onClick={onRetry}>
            Try again
          </Button>
        )}
      </Group>
    </Alert>
  )
}
