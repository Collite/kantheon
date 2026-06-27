// Investigation inbox client (Iris Phase 4 Stage 4.1 — PD-2). GET the aggregated
// view + an SSE stream that re-emits the view on each lifecycle change. At Phase 4
// the BFF aggregates a fake Pythia (empty until the Pythia arc), so the surface is
// live-wired but typically empty.
import { config } from '@/config'
import { useAuthStore } from '@/stores/auth'

export type InboxStatus = 'RUNNING' | 'NEEDS_INPUT' | 'DONE' | 'FAILED' | 'CANCELLED'

export interface InboxItem {
  investigationId: string
  question: string
  status: InboxStatus
  rawStatus: string
  origin: string
  costSoFar: number
  updatedAt: string
  sessionId?: string
  sessionTitle?: string
  turnId?: string
  partial: boolean
}

export interface InboxCounts {
  running: number
  needsInput: number
}

export interface InboxView {
  items: InboxItem[]
  counts: InboxCounts
}

const getHeaders = async (extra: Record<string, string> = {}): Promise<HeadersInit> => {
  const authStore = useAuthStore()
  const headers: Record<string, string> = { ...extra }
  if (authStore.userId) headers['X-User-ID'] = authStore.userId
  if (authStore.isAuthenticated) {
    await authStore.updateToken(5)
    if (authStore.token) headers['Authorization'] = `Bearer ${authStore.token}`
  }
  return headers
}

const EMPTY: InboxView = { items: [], counts: { running: 0, needsInput: 0 } }

export const inboxApi = {
  /** GET /v1/inbox → the aggregated view (empty on any failure). */
  async view(): Promise<InboxView> {
    try {
      const res = await fetch(`${config.bff.baseUrl}/v1/inbox`, { headers: await getHeaders() })
      if (!res.ok) return EMPTY
      return (await res.json()) as InboxView
    } catch {
      return EMPTY
    }
  },

  /** GET /v1/inbox/stream (SSE) — `onView` fires with each `inbox_event` snapshot.
   *  `onFail` fires when the stream can't open (non-ok response) or ends without a
   *  clean close, so the caller can fall back to polling `view()`. It is NOT
   *  invoked on a deliberate abort (the close handle below). Returns an abort
   *  handle; call it to close the stream. */
  stream(onView: (view: InboxView) => void, onFail?: () => void): () => void {
    const controller = new AbortController()
    void (async () => {
      try {
        const res = await fetch(`${config.bff.baseUrl}/v1/inbox/stream`, {
          headers: await getHeaders({ Accept: 'text/event-stream' }),
          signal: controller.signal,
        })
        // A non-ok response is not an event-stream — don't try to read its body;
        // signal the caller to fall back to polling.
        if (!res.ok) {
          onFail?.()
          return
        }
        const reader = res.body?.getReader()
        if (!reader) {
          onFail?.()
          return
        }
        const decoder = new TextDecoder()
        let buffer = ''
        let event: string | null = null
        let data: string | null = null
        // Dispatch a buffered `inbox_event` frame (shared by the blank-line
        // terminator and the flush-on-end below).
        const flush = () => {
          if (event === 'inbox_event' && data != null) {
            try {
              onView(JSON.parse(data) as InboxView)
            } catch {
              /* skip a malformed frame */
            }
          }
          event = null
          data = null
        }
        for (;;) {
          const { done, value } = await reader.read()
          if (done) {
            // Flush a final `data:` frame that lacked a trailing blank line, then
            // signal the (clean) end so the caller re-polls once.
            flush()
            onFail?.()
            break
          }
          buffer += decoder.decode(value, { stream: true })
          let nl: number
          while ((nl = buffer.indexOf('\n')) !== -1) {
            const line = buffer.slice(0, nl).trimEnd()
            buffer = buffer.slice(nl + 1)
            if (line.startsWith('event:')) event = line.slice('event:'.length).trim()
            else if (line.startsWith('data:')) data = line.slice('data:'.length).trim()
            else if (line === '') flush()
          }
        }
      } catch (err) {
        // A deliberate abort (the returned handle) is not a failure; anything else
        // is — let the caller fall back to polling.
        if (!(err instanceof DOMException && err.name === 'AbortError')) onFail?.()
      }
    })()
    return () => controller.abort()
  },
}
