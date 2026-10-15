import { Paper, Stack, Text, Title } from '@mantine/core'

/** Stands in for a page that a later phase of frontend/PLAN.md builds. */
export function PlaceholderPage({ title, phase, children }: { title: string; phase: string; children: string }) {
  return (
    <Stack maw={720}>
      <Title order={2}>{title}</Title>
      <Paper withBorder p="md">
        <Text>{children}</Text>
        <Text size="sm" c="dimmed" mt="xs">
          Built in phase {phase} of frontend/PLAN.md.
        </Text>
      </Paper>
    </Stack>
  )
}
