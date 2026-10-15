import { describe, expect, it } from 'vitest'

import { ApiProblem, toProblem } from '../../src/api/problem'

describe('toProblem', () => {
  it('reads a ledger problem body', () => {
    const problem = toProblem(422, {
      type: 'https://ledger.example/errors/insufficient-funds',
      title: 'Insufficient funds',
      status: 422,
      detail: 'Account x does not hold enough money for this amount.',
      code: 'INSUFFICIENT_FUNDS',
      requestId: 'req-1',
    })

    expect(problem).toBeInstanceOf(ApiProblem)
    expect(problem.code).toBe('INSUFFICIENT_FUNDS')
    expect(problem.status).toBe(422)
    expect(problem.requestId).toBe('req-1')
    expect(problem.errors).toEqual([])
  })

  it('keeps validation errors and finds them by field', () => {
    const problem = toProblem(400, {
      title: 'Request is not valid',
      detail: 'One or more fields are invalid.',
      code: 'VALIDATION_FAILED',
      errors: [{ field: 'name', message: 'must not be blank' }],
    })

    expect(problem.fieldError('name')).toBe('must not be blank')
    expect(problem.fieldError('currency')).toBeUndefined()
  })

  it('treats a proxy error page as the backend being unreachable', () => {
    const problem = toProblem(502, '<html><title>502 Bad Gateway</title></html>')

    expect(problem.code).toBe('BACKEND_UNREACHABLE')
  })

  it('reports any other non-ledger response with its status', () => {
    const problem = toProblem(405, '')

    expect(problem.code).toBe('HTTP_405')
  })
})
