import createClient, { type Middleware } from 'openapi-fetch'

import { ledgerStatus } from '../lib/ledgerStatus'
import { ApiProblem, toProblem, unreachable } from './problem'
import type { paths } from './schema'

/** Adds the admin key to /admin calls and records the hold and request-id headers of every response. */
const ledgerHeaders: Middleware = {
  onRequest({ request, schemaPath }) {
    const adminKey = ledgerStatus.get().adminKey
    if (adminKey && schemaPath.startsWith('/admin/')) {
      request.headers.set('X-Admin-Key', adminKey)
    }
    return request
  },
  onResponse({ response }) {
    ledgerStatus.recordResponse(response)
    return response
  },
}

/** Typed client for the ledger API. Paths and bodies are checked against schema.ts (npm run gen:api). */
export const api = createClient<paths>({ baseUrl: '/api' })
api.use(ledgerHeaders)

type Result<T> = { data?: T; error?: unknown; response: Response }

/**
 * Awaits an openapi-fetch call and returns its data, or throws an ApiProblem. Use it inside TanStack Query
 * functions so every screen gets the same error type.
 */
export async function unwrap<T>(call: Promise<Result<T>>): Promise<T> {
  let result: Result<T>
  try {
    result = await call
  } catch {
    throw unreachable()
  }
  if (result.error !== undefined || !result.response.ok) {
    throw toProblem(result.response.status, result.error)
  }
  return result.data as T
}

export { ApiProblem }
