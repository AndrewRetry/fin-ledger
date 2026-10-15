/** "8398f93a-24c5-3661-8e9a-33d54273d824" -> "8398f93a…d824"; short enough for a table, unique enough to read. */
export function shortId(id: string): string {
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id
}
