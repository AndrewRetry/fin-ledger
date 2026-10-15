import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api, unwrap } from './client'
import type { components } from './schema'

/** The newest link of the hash chain. Polled, so the header shows writes from other tabs and scripts too. */
export function useLedgerHead() {
  return useQuery({
    queryKey: ['ledger-head'],
    queryFn: () => unwrap(api.GET('/ledger/head')),
    refetchInterval: 10_000,
  })
}

/** One page of accounts, oldest first. Keeps the previous page on screen while the next one loads. */
export function useAccounts(page: number, size: number) {
  return useQuery({
    queryKey: ['accounts', page, size],
    queryFn: () => unwrap(api.GET('/accounts', { params: { query: { page, size } } })),
    placeholderData: keepPreviousData,
  })
}

export function useAccount(id: string) {
  return useQuery({
    queryKey: ['account', id],
    queryFn: () => unwrap(api.GET('/accounts/{id}', { params: { path: { id } } })),
  })
}

/** One page of an account's postings, oldest first (by account version). */
export function useAccountHistory(id: string, page: number, size: number) {
  return useQuery({
    queryKey: ['history', id, page, size],
    queryFn: () => unwrap(api.GET('/accounts/{id}/transactions', { params: { path: { id }, query: { page, size } } })),
    placeholderData: keepPreviousData,
  })
}

export function useCreateAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { name: string; currency: string }) => unwrap(api.POST('/accounts', { body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['accounts'] }),
  })
}

export type Account = components['schemas']['AccountResponse']

/** Every account, for pickers and name lookups (pages through GET /accounts; the demo has few). */
export function useAllAccounts() {
  return useQuery({
    queryKey: ['accounts', 'all'],
    queryFn: async () => {
      const all: Account[] = []
      for (let page = 0; ; page++) {
        const result = await unwrap(api.GET('/accounts', { params: { query: { page, size: 200 } } }))
        all.push(...result.items)
        if (page + 1 >= result.totalPages) {
          return all
        }
      }
    },
  })
}

/** The account with this id, from the shared all-accounts list. */
export function useAccountById(id: string | null): Account | undefined {
  const accounts = useAllAccounts()
  return id ? accounts.data?.find((account) => account.id === id) : undefined
}

/** The rate in use for every currency pair. */
export function useFxRates() {
  return useQuery({ queryKey: ['fx-rates'], queryFn: () => unwrap(api.GET('/fx/rates')) })
}

/** One stored transaction, including reversedById (which POST answers leave out). */
export function useTransaction(id: string) {
  return useQuery({
    queryKey: ['transaction', id],
    queryFn: () => unwrap(api.GET('/transactions/{id}', { params: { path: { id } } })),
  })
}

/** Hold status from the admin endpoint (needs the admin key; the header alone says only held or not). */
export function useHoldStatus(enabled: boolean) {
  return useQuery({
    queryKey: ['hold-status'],
    queryFn: () => unwrap(api.GET('/admin/integrity-hold')),
    enabled,
    retry: false,
  })
}

/** Admin writes. Either can change what every screen shows, so everything is refetched afterwards. */
export function useAdminActions() {
  const queryClient = useQueryClient()
  const refreshAll = () => queryClient.invalidateQueries()
  return {
    addRate: useMutation({
      mutationFn: (body: { base: string; quote: string; rate: string }) => unwrap(api.POST('/admin/fx-rates', { body })),
      onSuccess: refreshAll,
    }),
    releaseHold: useMutation({
      mutationFn: (reason: string) => unwrap(api.POST('/admin/integrity-hold/release', { body: { reason } })),
      onSuccess: refreshAll,
    }),
  }
}
