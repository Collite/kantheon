import { config } from '@/config'
import { useSessionStore } from '@/stores/session'

/** Error carrying the BFF/Midas-core status + parsed body for the UI to surface. */
export class BffError extends Error {
  constructor(
    readonly status: number,
    readonly body: unknown,
  ) {
    super(`BFF ${status}`)
  }
}

/**
 * Fetch wrapper for the BFF. Attaches the session bearer (the BFF re-derives the
 * tenant from it and forwards `X-Tenant-Id`). JSON in/out; throws [BffError] on a
 * non-2xx so TanStack Query surfaces it.
 */
export async function bff<T = unknown>(
  path: string,
  opts: { method?: string; body?: unknown; query?: Record<string, string | number | boolean | undefined> } = {},
): Promise<T> {
  const session = useSessionStore()
  const qs = opts.query
    ? '?' +
      Object.entries(opts.query)
        .filter(([, v]) => v !== undefined && v !== '')
        .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
        .join('&')
    : ''
  const res = await fetch(`${config.bffBase}${path}${qs}`, {
    method: opts.method ?? 'GET',
    headers: {
      ...(session.bearer ? { Authorization: `Bearer ${session.bearer}` } : {}),
      ...(opts.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })
  const text = await res.text()
  const parsed = text ? safeJson(text) : null
  if (!res.ok) throw new BffError(res.status, parsed)
  return parsed as T
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}
