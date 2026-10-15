import { ledgerStatus } from '../lib/ledgerStatus'

/** Responses are checked field by field, so their bodies are read as loosely typed JSON. */
export type Json = any

export type Reply = { status: number; json: Json; text: string; headers: Headers }

/** One request a scenario made and what came back, shown under the scenario so nothing is hidden. */
export type Exchange = { method: string; path: string; key?: string; request?: unknown; status: number; response: unknown }

export type Step = { name: string; ok: boolean; detail?: string }

export type Outcome = { steps: Step[]; exchanges: Exchange[]; passed: boolean }

export type Scenario = {
  id: string
  title: string
  proves: string
  run: (ctx: ScenarioContext) => Promise<void>
}

const MONEY_MOVING = /^\/(deposits|withdrawals|transactions)(\/|$)/

/**
 * What a scenario uses: raw HTTP through the same /api proxy as the console (so status codes and headers are
 * exactly what a client sees), checks that record PASS/FAIL, and helpers to set up its own accounts.
 */
export class ScenarioContext {
  readonly steps: Step[] = []
  readonly exchanges: Exchange[] = []

  /** POSTs to money-moving paths get a fresh Idempotency-Key unless one is given, or `null` to send none. */
  async post(path: string, body?: unknown, key?: string | null): Promise<Reply> {
    const idempotencyKey = key === undefined && MONEY_MOVING.test(path) ? crypto.randomUUID() : key ?? undefined
    return this.call('POST', path, body, idempotencyKey)
  }

  get(path: string): Promise<Reply> {
    return this.call('GET', path)
  }

  /** Records a step: PASS when actual equals expected. Returns whether it passed. */
  expect(name: string, actual: unknown, expected: unknown): boolean {
    const ok = actual === expected
    this.steps.push({ name, ok, detail: ok ? undefined : `expected ${String(expected)}, got ${String(actual)}` })
    return ok
  }

  /** "422 INSUFFICIENT_FUNDS" — the status and the problem code, as one comparable string. */
  static outcome(reply: Reply): string {
    return `${reply.status} ${reply.json?.code ?? ''}`.trim()
  }

  async customer(name: string, currency: string): Promise<string> {
    const reply = await this.post('/accounts', { name: `Scenario ${name}`, currency })
    if (reply.status !== 201) {
      throw new Error(`could not create account ${name}: ${reply.status} ${reply.text}`)
    }
    return reply.json.id
  }

  async funded(name: string, currency: string, amount: number): Promise<string> {
    const id = await this.customer(name, currency)
    const reply = await this.post('/deposits', { accountId: id, amount })
    if (reply.status !== 201) {
      throw new Error(`could not fund ${name}: ${reply.status} ${reply.text}`)
    }
    return id
  }

  async balance(id: string): Promise<number> {
    return (await this.get(`/accounts/${id}`)).json.balance.amount
  }

  transfer(source: string, destination: string, amount: number, key?: string | null): Promise<Reply> {
    return this.post('/transactions', { sourceAccountId: source, destinationAccountId: destination, amount }, key)
  }

  private async call(method: string, path: string, body?: unknown, key?: string): Promise<Reply> {
    const headers: Record<string, string> = {}
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }
    if (key) {
      headers['Idempotency-Key'] = key
    }
    const response = await fetch(`/api${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
    })
    ledgerStatus.recordResponse(response)
    const text = await response.text()
    let json: Json = null
    try {
      json = text ? JSON.parse(text) : null
    } catch {
      json = null
    }
    this.exchanges.push({ method, path, key, request: body, status: response.status, response: json ?? text })
    return { status: response.status, json, text, headers: response.headers }
  }
}

/** Runs one scenario. An unexpected error becomes a failed step instead of stopping the page. */
export async function runScenario(scenario: Scenario): Promise<Outcome> {
  const ctx = new ScenarioContext()
  try {
    await scenario.run(ctx)
  } catch (error) {
    ctx.steps.push({ name: 'Scenario stopped by an unexpected error', ok: false, detail: String(error) })
  }
  return { steps: ctx.steps, exchanges: ctx.exchanges, passed: ctx.steps.length > 0 && ctx.steps.every((s) => s.ok) }
}
