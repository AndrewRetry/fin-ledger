import { ActionIcon, Anchor, Collapse, Group, Stack, Text, TextInput, Tooltip } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { IconChevronDown, IconChevronRight, IconRefresh } from '@tabler/icons-react'

/** Collapsed "Request details": the key this form will send, editable, with a button for a new one. */
export function IdempotencyField({ value, onChange, onRenew }: {
  value: string
  onChange: (key: string) => void
  onRenew: () => void
}) {
  const [opened, { toggle }] = useDisclosure(false)
  return (
    <Stack gap={4}>
      <Anchor component="button" type="button" size="sm" c="dimmed" onClick={toggle}>
        <Group gap={4}>
          {opened ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
          Request details (Idempotency-Key)
        </Group>
      </Anchor>
      <Collapse expanded={opened}>
        <Stack gap={4}>
          <TextInput
            value={value}
            onChange={(event) => onChange(event.currentTarget.value)}
            ff="monospace"
            aria-label="Idempotency-Key"
            rightSection={
              <Tooltip label="New key" withArrow>
                <ActionIcon variant="subtle" color="gray" onClick={onRenew} aria-label="New key">
                  <IconRefresh size={16} />
                </ActionIcon>
              </Tooltip>
            }
          />
          <Text size="xs" c="dimmed">
            Same key + same body replays the first result instead of moving money again. Same key + a different body
            is refused (422). A request that was rejected wrote nothing, so its key can be used again.
          </Text>
        </Stack>
      </Collapse>
    </Stack>
  )
}
