import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api, unwrap } from './client'

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
