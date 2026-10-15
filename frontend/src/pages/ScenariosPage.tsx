import { Accordion, Badge, Button, Code, Group, Paper, ScrollArea, Stack, Text, ThemeIcon, Title } from '@mantine/core'
import { useQueryClient } from '@tanstack/react-query'
import { IconCheck, IconPlayerPlay, IconPlayerTrackNext, IconX } from '@tabler/icons-react'
import { useState } from 'react'

import { type Outcome, runScenario, type Scenario } from '../scenarios/runner'
import { SCENARIOS } from '../scenarios/scenarios'

type State = { running: boolean; outcome?: Outcome }

/** Every requirement of the assignment, runnable in the browser, with the evidence for each step. */
export function ScenariosPage() {
  const [states, setStates] = useState<Record<string, State>>({})
  const [runningAll, setRunningAll] = useState(false)
  const queryClient = useQueryClient()

  async function run(scenario: Scenario) {
    setStates((current) => ({ ...current, [scenario.id]: { running: true } }))
    const outcome = await runScenario(scenario)
    setStates((current) => ({ ...current, [scenario.id]: { running: false, outcome } }))
    return outcome
  }

  async function runAll() {
    setRunningAll(true)
    for (const scenario of SCENARIOS) {
      await run(scenario)
    }
    setRunningAll(false)
    queryClient.invalidateQueries()
  }

  const finished = SCENARIOS.map((s) => states[s.id]?.outcome).filter((o): o is Outcome => Boolean(o))
  const passed = finished.filter((o) => o.passed).length

  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <div>
          <Title order={2}>Scenarios</Title>
          <Text c="dimmed" size="sm" maw={760}>
            Each card runs one requirement against the live API, with its own fresh accounts, and checks every answer.
            Open "Requests" under a card to see exactly what was sent and what came back.
          </Text>
        </div>
        <Group>
          {finished.length > 0 && (
            <Badge size="lg" variant="light" color={passed === finished.length ? 'teal' : 'red'}>
              {passed} / {finished.length} passed
            </Badge>
          )}
          <Button leftSection={<IconPlayerTrackNext size={16} />} onClick={runAll} loading={runningAll}>
            Run all
          </Button>
        </Group>
      </Group>
      {SCENARIOS.map((scenario) => (
        <ScenarioCard key={scenario.id} scenario={scenario} state={states[scenario.id]} disabled={runningAll}
          onRun={() => run(scenario).then(() => queryClient.invalidateQueries())} />
      ))}
    </Stack>
  )
}

function ScenarioCard({ scenario, state, disabled, onRun }: {
  scenario: Scenario
  state?: State
  disabled: boolean
  onRun: () => void
}) {
  const outcome = state?.outcome
  return (
    <Paper withBorder p="md">
      <Stack gap="xs">
        <Group justify="space-between" wrap="nowrap" align="flex-start">
          <div>
            <Group gap="xs">
              <Text fw={600}>{scenario.title}</Text>
              {state?.running && <Badge variant="light" color="blue">Running</Badge>}
              {outcome && (
                <Badge color={outcome.passed ? 'teal' : 'red'}
                  leftSection={outcome.passed ? <IconCheck size={12} /> : <IconX size={12} />}>
                  {outcome.passed ? 'Passed' : 'Failed'}
                </Badge>
              )}
            </Group>
            <Text size="sm" c="dimmed">{scenario.proves}</Text>
          </div>
          <Button variant="light" size="xs" leftSection={<IconPlayerPlay size={14} />} onClick={onRun}
            loading={state?.running} disabled={disabled && !state?.running} style={{ flexShrink: 0 }}>
            Run
          </Button>
        </Group>

        {outcome && (
          <>
            <Stack gap={4}>
              {outcome.steps.map((step, i) => (
                <Group key={i} gap="xs" wrap="nowrap" align="flex-start">
                  <ThemeIcon size={18} radius="xl" variant="light" color={step.ok ? 'teal' : 'red'} style={{ flexShrink: 0 }}>
                    {step.ok ? <IconCheck size={12} /> : <IconX size={12} />}
                  </ThemeIcon>
                  <Text size="sm">
                    {step.name}
                    {step.detail && <Text span c="red" size="sm"> — {step.detail}</Text>}
                  </Text>
                </Group>
              ))}
            </Stack>
            <Accordion variant="contained" chevronPosition="left">
              <Accordion.Item value="requests">
                <Accordion.Control>
                  <Text size="sm">Requests ({outcome.exchanges.length})</Text>
                </Accordion.Control>
                <Accordion.Panel>
                  <ScrollArea.Autosize mah={420} type="auto">
                    <Stack gap="xs">
                      {outcome.exchanges.map((exchange, i) => (
                        <Stack key={i} gap={2}>
                          <Group gap="xs">
                            <Badge variant="outline" radius="sm" size="sm">{exchange.method}</Badge>
                            <Text size="sm" ff="monospace">{exchange.path}</Text>
                            <Badge size="sm" variant="light"
                              color={exchange.status < 300 ? 'teal' : exchange.status < 500 ? 'orange' : 'red'}>
                              {exchange.status}
                            </Badge>
                            {exchange.key && <Text size="xs" c="dimmed" ff="monospace">key {exchange.key}</Text>}
                          </Group>
                          <Code block fz="xs">{JSON.stringify(exchange.response, null, 2)}</Code>
                        </Stack>
                      ))}
                    </Stack>
                  </ScrollArea.Autosize>
                </Accordion.Panel>
              </Accordion.Item>
            </Accordion>
          </>
        )}
      </Stack>
    </Paper>
  )
}
