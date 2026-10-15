import { useQuery } from '@tanstack/react-query'

import { api, unwrap } from './client'

/** The newest link of the hash chain. Polled, so the header shows writes from other tabs and scripts too. */
export function useLedgerHead() {
  return useQuery({
    queryKey: ['ledger-head'],
    queryFn: () => unwrap(api.GET('/ledger/head')),
    refetchInterval: 10_000,
  })
}
