import { useMutation, useQueryClient } from '@tanstack/react-query'

import { api, send } from './client'
import type { components } from './schema'

/** A stored transaction. Fields the backend may send as null are typed as strings by the generator. */
export type Transaction = components['schemas']['TransactionView']
type Schemas = components['schemas']

/** The answer to a money-moving request, and whether it was an earlier transaction replayed for the same key. */
export type Posted = { tx: Transaction; replayed: boolean }

/** Request bodies with their optional fields marked optional (the generator marks every field required). */
export type TransferBody = {
  sourceAccountId: string
  destinationAccountId: string
  amount: number
  currency?: string
  description?: string
}
export type MovementBody = { accountId: string; amount: number; description?: string; externalRef?: string }

export type MoneyRequest =
  | { kind: 'transfer'; body: TransferBody }
  | { kind: 'deposit'; body: MovementBody }
  | { kind: 'withdraw'; body: MovementBody }
  | { kind: 'reverse'; transactionId: string; body: { reason: string } }

/** Sends one money-moving request with its Idempotency-Key. */
async function post(request: MoneyRequest, key: string): Promise<Posted> {
  const header = { 'Idempotency-Key': key }
  const call = (() => {
    switch (request.kind) {
      case 'transfer':
        return api.POST('/transactions', { params: { header }, body: request.body as Schemas['TransferRequest'] })
      case 'deposit':
        return api.POST('/deposits', { params: { header }, body: request.body as Schemas['MoneyMovementRequest'] })
      case 'withdraw':
        return api.POST('/withdrawals', { params: { header }, body: request.body as Schemas['MoneyMovementRequest'] })
      case 'reverse':
        return api.POST('/transactions/{id}/reversals', {
          params: { header, path: { id: request.transactionId } },
          body: request.body,
        })
    }
  })()
  const { data, response } = await send(call)
  return { tx: data, replayed: response.headers.get('Idempotent-Replayed') === 'true' }
}

/** After any write, every balance, history and the chain head may have changed: refetch them all. */
export function useMoneyRequest() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ request, key }: { request: MoneyRequest; key: string }) => post(request, key),
    onSettled: () => queryClient.invalidateQueries(),
  })
}
