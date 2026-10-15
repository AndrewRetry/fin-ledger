import { useSyncExternalStore } from 'react'

/**
 * What the console knows about the ledger from the responses it has seen, plus the admin key for this browser
 * session. A tiny store instead of React context, so the API client (outside React) can update it.
 */
export type LedgerStatus = {
  /** True while responses carry X-Ledger-Integrity: HOLD; null until the first response arrives. */
  held: boolean | null
  /** X-Request-Id of the newest response, to quote when reporting a problem. */
  lastRequestId: string | null
  /** Kept in sessionStorage only: gone when the tab closes, never logged or put in a URL. */
  adminKey: string | null
}

const ADMIN_KEY_STORAGE = 'ledger-console.admin-key'

let state: LedgerStatus = {
  held: null,
  lastRequestId: null,
  adminKey: sessionStorage.getItem(ADMIN_KEY_STORAGE),
}
const listeners = new Set<() => void>()

function update(change: Partial<LedgerStatus>) {
  state = { ...state, ...change }
  listeners.forEach((listener) => listener())
}

export const ledgerStatus = {
  get: () => state,

  /** Called by the API client for every response. */
  recordResponse(response: Response) {
    update({
      held: response.headers.get('X-Ledger-Integrity') === 'HOLD',
      lastRequestId: response.headers.get('X-Request-Id') ?? state.lastRequestId,
    })
  },

  setAdminKey(key: string | null) {
    if (key) {
      sessionStorage.setItem(ADMIN_KEY_STORAGE, key)
    } else {
      sessionStorage.removeItem(ADMIN_KEY_STORAGE)
    }
    update({ adminKey: key })
  },

  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
}

export function useLedgerStatus(): LedgerStatus {
  return useSyncExternalStore(ledgerStatus.subscribe, ledgerStatus.get)
}
