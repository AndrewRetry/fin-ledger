import { Stack, Text } from '@mantine/core'
import { IconInbox } from '@tabler/icons-react'

/** What an empty list shows instead of a blank table. */
export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <Stack align="center" gap={4} py="xl" c="dimmed">
      <IconInbox size={32} stroke={1.4} />
      <Text fw={500}>{title}</Text>
      {hint && <Text size="sm">{hint}</Text>}
    </Stack>
  )
}
