/** Pretty-print a JSON-ish audit payload for the before/after panes. */
export function prettyJson(value: unknown): string {
  if (value === null || value === undefined) return '—'
  try {
    const obj = typeof value === 'string' ? JSON.parse(value) : value
    return JSON.stringify(obj, null, 2)
  } catch {
    return String(value)
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  if (value === null || value === undefined) return {}
  try {
    const obj = typeof value === 'string' ? JSON.parse(value) : value
    return obj && typeof obj === 'object' ? (obj as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

/**
 * The set of top-level keys whose value differs between before and after — drives
 * the "changed: …" line and (later) field-level highlighting. A missing key on
 * either side counts as changed; deep values compare by JSON string.
 */
export function diffKeys(before: unknown, after: unknown): Set<string> {
  const b = asRecord(before)
  const a = asRecord(after)
  const keys = new Set([...Object.keys(b), ...Object.keys(a)])
  const changed = new Set<string>()
  for (const k of keys) {
    if (JSON.stringify(b[k]) !== JSON.stringify(a[k])) changed.add(k)
  }
  return changed
}
