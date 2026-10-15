import { ActionIcon, Anchor, CopyButton, Group, Text, Tooltip } from '@mantine/core'
import { IconCheck, IconCopy } from '@tabler/icons-react'
import { Link } from 'react-router'

import { shortId } from '../lib/ids'

/** An id or hash: shortened, full value on hover, one-click copy, optionally a link to its page. */
export function IdCell({ value, to }: { value: string; to?: string }) {
  const text = (
    <Text component="span" ff="monospace" size="sm">
      {shortId(value)}
    </Text>
  )
  return (
    <Group gap={2} wrap="nowrap">
      <Tooltip label={value} withArrow openDelay={300}>
        {to ? (
          <Anchor component={Link} to={to} underline="hover">
            {text}
          </Anchor>
        ) : (
          text
        )}
      </Tooltip>
      <CopyButton value={value}>
        {({ copied, copy }) => (
          <Tooltip label={copied ? 'Copied' : 'Copy'} withArrow>
            <ActionIcon size="sm" variant="subtle" color="gray" onClick={copy} aria-label="Copy">
              {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
            </ActionIcon>
          </Tooltip>
        )}
      </CopyButton>
    </Group>
  )
}
