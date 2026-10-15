import { useState } from 'react'

/** The Idempotency-Key for the next request: a fresh UUID, kept after a success so a retry can replay it. */
export function useIdempotencyKey() {
  const [key, setKey] = useState<string>(() => crypto.randomUUID())
  return { key, setKey, renew: () => setKey(crypto.randomUUID()) }
}
