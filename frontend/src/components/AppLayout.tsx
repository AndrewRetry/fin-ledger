import {
  ActionIcon,
  AppShell,
  Badge,
  Burger,
  Group,
  NavLink,
  ScrollArea,
  Text,
  Tooltip,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import {
  IconApi,
  IconArrowsExchange,
  IconBuildingBank,
  IconCoin,
  IconKey,
  IconLayoutDashboard,
  IconListCheck,
  IconLock,
  IconLockOpen,
  IconMoon,
  IconReceipt,
  IconShieldCheck,
  IconSun,
  IconUsers,
} from '@tabler/icons-react'
import { Link, Outlet, useLocation } from 'react-router'

import { useLedgerHead } from '../api/queries'
import { HoldBanner } from './HoldBanner'
import { shortId } from '../lib/ids'
import { useLedgerStatus } from '../lib/ledgerStatus'

const NAV = [
  { to: '/', label: 'Overview', icon: IconLayoutDashboard },
  { to: '/accounts', label: 'Accounts', icon: IconUsers },
  { to: '/move', label: 'Move money', icon: IconArrowsExchange },
  { to: '/transactions', label: 'Transactions', icon: IconReceipt },
  { to: '/fx', label: 'FX rates', icon: IconCoin },
  { to: '/integrity', label: 'Integrity', icon: IconShieldCheck },
  { to: '/scenarios', label: 'Scenarios', icon: IconListCheck },
  { to: '/admin', label: 'Admin', icon: IconKey },
]

/** Fixed header and navbar; only the main area scrolls, so nothing overlaps at any width. */
export function AppLayout() {
  const [opened, { toggle, close }] = useDisclosure()
  const { pathname } = useLocation()

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 220, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="xs" wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Toggle navigation" />
            <IconBuildingBank size={22} style={{ flexShrink: 0 }} />
            <Text fw={600} visibleFrom="xs" style={{ whiteSpace: 'nowrap' }}>
              Ledger Console
            </Text>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <ChainHead />
            <HoldBadge />
            <ApiDocsLink />
            <ColorSchemeToggle />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        <AppShell.Section grow component={ScrollArea}>
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              component={Link}
              to={item.to}
              label={item.label}
              leftSection={<item.icon size={18} stroke={1.6} />}
              active={item.to === '/' ? pathname === '/' : pathname.startsWith(item.to)}
              onClick={close}
            />
          ))}
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main>
        <HoldBanner />
        <Outlet />
      </AppShell.Main>
    </AppShell>
  )
}

/** Newest link of the hash chain, refreshed every 10 seconds. */
function ChainHead() {
  const head = useLedgerHead()
  if (!head.data) {
    return null
  }
  return (
    <Tooltip label={`Head hash ${head.data.headHash}`} withArrow multiline maw={360}>
      <Text size="xs" c="dimmed" ff="monospace" visibleFrom="md">
        chain {head.data.chainLength} · {shortId(head.data.headHash)}
      </Text>
    </Tooltip>
  )
}

/** Writes are open, or stopped by an integrity hold (read from every response's X-Ledger-Integrity header). */
function HoldBadge() {
  const { held } = useLedgerStatus()
  if (held === null) {
    return null
  }
  return held ? (
    <Badge color="orange" variant="light" leftSection={<IconLock size={12} />} style={{ flexShrink: 0 }}>
      Integrity hold
    </Badge>
  ) : (
    <Badge color="teal" variant="light" leftSection={<IconLockOpen size={12} />} style={{ flexShrink: 0 }}>
      Writes open
    </Badge>
  )
}

/** Swagger UI is served by the backend itself on port 8080. */
function ApiDocsLink() {
  const href = `${window.location.protocol}//${window.location.hostname}:8080/swagger-ui.html`
  return (
    <Tooltip label="API docs (Swagger UI)" withArrow>
      <ActionIcon component="a" href={href} target="_blank" rel="noreferrer" variant="default" aria-label="API docs">
        <IconApi size={18} />
      </ActionIcon>
    </Tooltip>
  )
}

function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme()
  const scheme = useComputedColorScheme('light')
  return (
    <Tooltip label={scheme === 'dark' ? 'Light mode' : 'Dark mode'} withArrow>
      <ActionIcon
        variant="default"
        onClick={() => setColorScheme(scheme === 'dark' ? 'light' : 'dark')}
        aria-label="Toggle colour scheme"
      >
        {scheme === 'dark' ? <IconSun size={18} /> : <IconMoon size={18} />}
      </ActionIcon>
    </Tooltip>
  )
}
