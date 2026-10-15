const LOCAL = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' })

/** "15 Oct 2026, 13:22:52" in the viewer's time zone; the backend stores and sends UTC. */
export function formatTime(iso: string): string {
  return LOCAL.format(new Date(iso))
}
