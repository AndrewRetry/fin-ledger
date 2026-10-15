/** One field that failed validation, as the backend reports it. */
export type FieldError = { field: string; message: string }

/**
 * Any failed request, in the backend's RFC 9457 shape. Requests that never reached the backend (it is still
 * starting, or down) become code BACKEND_UNREACHABLE, so every screen handles one error type.
 */
export class ApiProblem extends Error {
  readonly status: number
  readonly code: string
  readonly title: string
  readonly detail: string
  readonly requestId: string | null
  readonly errors: FieldError[]

  constructor(fields: {
    status: number
    code: string
    title: string
    detail: string
    requestId?: string | null
    errors?: FieldError[]
  }) {
    super(`${fields.code}: ${fields.detail}`)
    this.status = fields.status
    this.code = fields.code
    this.title = fields.title
    this.detail = fields.detail
    this.requestId = fields.requestId ?? null
    this.errors = fields.errors ?? []
  }

  /** The message for one form field, if the backend rejected it. */
  fieldError(field: string): string | undefined {
    return this.errors.find((error) => error.field === field)?.message
  }
}

/** The backend could not be reached at all: show a "starting up" message rather than a raw proxy page. */
export function unreachable(): ApiProblem {
  return new ApiProblem({
    status: 0,
    code: 'BACKEND_UNREACHABLE',
    title: 'Backend not reachable',
    detail: 'The ledger API did not answer. If you just ran docker compose up, it needs about 30 seconds to start.',
  })
}

/** Turns an error response into an ApiProblem. A body without a `code` did not come from the ledger itself. */
export function toProblem(status: number, body: unknown): ApiProblem {
  if (typeof body === 'object' && body !== null && 'code' in body) {
    const problem = body as Record<string, unknown>
    return new ApiProblem({
      status,
      code: String(problem.code),
      title: String(problem.title ?? 'Request failed'),
      detail: String(problem.detail ?? ''),
      requestId: typeof problem.requestId === 'string' ? problem.requestId : null,
      errors: Array.isArray(problem.errors) ? (problem.errors as FieldError[]) : [],
    })
  }
  if (status === 502 || status === 503 || status === 504) {
    return unreachable()
  }
  return new ApiProblem({
    status,
    code: `HTTP_${status}`,
    title: 'Unexpected response',
    detail: typeof body === 'string' && body.length < 300 ? body : `The server answered with status ${status}.`,
  })
}
