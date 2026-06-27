// iris-bff client (Iris Phase 2 Stage 2.2 — the FE talks ONLY to the BFF).
//
// Replaces the direct new-golem /v2 client. Targets the BFF /v1/* REST + SSE
// surface (contracts §2). Every call carries the Keycloak bearer (OBO): the BFF
// requires it on all /v1 routes and forwards it downstream.
//
// SSE note: the chat endpoints are POST with a JSON body, so EventSource
// (GET-only) can't be used — we hand-parse the text/event-stream body over fetch
// + ReadableStream. Each SSE frame's `data:` line is one proto-JSON iris/v1
// IrisStreamEvent; the event NAME is the oneof case. The v2 dispatch path emits
// only `step` | `envelope` | `error` | `done` (tool_call/thinking are declared in
// the proto but never produced today). Proto-JSON omits zero/false/empty fields,
// so read defensively.
import { config } from '@/config'
import { useAuthStore } from '@/stores/auth'
import {
  FormatEnvelope,
  type StepEvent,
  type ErrorEvent,
  type DoneEvent,
} from '@/types/envelope'
import type {
  ChatResumeRequestDto,
  ChatTurnRequestDto,
  RefreshResponseDto,
  SessionDto,
  SessionSummaryDto,
  TurnEnvelopeDto,
} from '@/types/agent-responses'

// An agent id from the configured registry. With the single-BFF registry it is
// just 'iris'; the type is retained for the multi-agent surface that Phase 3
// (server-side routing) reintroduces.
export type AgentKey = string

// ----- Stream callbacks (iris/v1 IrisStreamEvent arms) -----
export interface StreamHandlers {
  /** Node lifecycle step; `phase` = started | completed | failed. */
  onStep?: (step: StepEvent) => void
  /** A rendered envelope/v1 bubble. */
  onEnvelope?: (envelope: FormatEnvelope) => void
  /** A non-recoverable turn error. */
  onError?: (error: ErrorEvent) => void
  /** Stream close; `outcome` = done | failed | clarification. */
  onDone?: (done: DoneEvent) => void
  /** The server turn id, surfaced once from the first event that carries it
   *  (every IrisStreamEvent wraps the same `turnId`). Lets the FE tag the
   *  user/assistant bubbles so a later `edit_resend` can name `fromTurnId`. */
  onTurnId?: (turnId: string) => void
}

const baseUrl = (): string => config.bff.baseUrl

// Build request headers, attaching the Keycloak bearer when authenticated. The
// token rides in `Authorization` so the BFF (and the Envoy JWT policy) accept the
// request — this is why the fetch-based SSE turn works under JWT (EventSource
// couldn't carry it). Async because `updateToken` may refresh an expiring token.
const getHeaders = async (extra: Record<string, string> = {}): Promise<HeadersInit> => {
  const authStore = useAuthStore()
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...extra }
  if (authStore.userId) {
    headers['X-User-ID'] = authStore.userId
  }
  if (authStore.isAuthenticated) {
    await authStore.updateToken(5)
    if (authStore.token) {
      headers['Authorization'] = `Bearer ${authStore.token}`
    }
  }
  return headers
}

// Parse a text/event-stream body, dispatching each IrisStreamEvent to handlers.
async function consumeSse(response: Response, handlers: StreamHandlers): Promise<void> {
  const reader = response.body?.getReader()
  if (!reader) throw new Error('stream: no response body')
  const decoder = new TextDecoder()

  let buffer = ''
  let currentEvent: string | null = null
  let dataLines: string[] = []

  let turnIdSeen = false

  const dispatch = (event: string, raw: string) => {
    let payload: Record<string, unknown>
    try {
      payload = JSON.parse(raw) as Record<string, unknown>
    } catch (e) {
      console.warn('[irisStream] bad JSON payload', e, raw)
      return
    }
    if (!turnIdSeen && typeof payload.turnId === 'string' && payload.turnId) {
      turnIdSeen = true
      handlers.onTurnId?.(payload.turnId)
    }
    switch (event) {
      case 'step':
        if (payload.step) handlers.onStep?.(payload.step as StepEvent)
        break
      case 'envelope':
        try {
          if (payload.envelope) {
            // The BFF normalises v2→envelope/v1 on its wire (IrisStreamMux), so
            // decode the envelope/v1 JSON directly — no v2 shim at this edge.
            handlers.onEnvelope?.(FormatEnvelope.fromJSON(payload.envelope))
          }
        } catch (e) {
          handlers.onError?.({
            code: 'BAD_ENVELOPE',
            message: e instanceof Error ? e.message : String(e),
            recoverable: false,
          })
        }
        break
      case 'error':
        if (payload.error) handlers.onError?.(payload.error as ErrorEvent)
        break
      case 'done':
        handlers.onDone?.((payload.done ?? { outcome: 'done' }) as DoneEvent)
        break
      // ':heartbeat' comment frames and unmodelled arms (tool_call/thinking) are ignored.
    }
  }

  const flushEvent = () => {
    if (currentEvent && dataLines.length > 0) {
      dispatch(currentEvent, dataLines.join('\n'))
    }
    currentEvent = null
    dataLines = []
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        flushEvent()
        break
      }
      buffer += decoder.decode(value, { stream: true })
      // SSE frames are separated by blank lines; within a frame, lines are
      // `event: <name>` and `data: <json>`.
      let nlIdx: number
      while ((nlIdx = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, nlIdx).trimEnd()
        buffer = buffer.slice(nlIdx + 1)
        if (line === '') {
          flushEvent()
          continue
        }
        if (line.startsWith('event:')) {
          currentEvent = line.slice('event:'.length).trim()
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice('data:'.length).trim())
        }
        // `id:`, `retry:` and `:`-comment (heartbeat) lines are ignored — a
        // single-turn stream needs no re-connection semantics.
      }
    }
  } finally {
    reader.releaseLock()
  }
}

async function postStream(
  path: string,
  body: unknown,
  handlers: StreamHandlers,
): Promise<void> {
  const response = await fetch(`${baseUrl()}${path}`, {
    method: 'POST',
    headers: await getHeaders({ Accept: 'text/event-stream' }),
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    throw new Error(`${path} failed: HTTP ${response.status}`)
  }
  await consumeSse(response, handlers)
}

export const irisStream = {
  /** POST /v1/session — mint a fresh server session (carries the discovery surface). */
  async createSession(): Promise<SessionDto> {
    const res = await fetch(`${baseUrl()}/v1/session`, {
      method: 'POST',
      headers: await getHeaders(),
    })
    if (!res.ok) throw new Error(`createSession failed: HTTP ${res.status}`)
    return res.json() as Promise<SessionDto>
  },

  /** GET /v1/sessions — the authenticated caller's session summaries. */
  async listSessions(): Promise<SessionSummaryDto[]> {
    const res = await fetch(`${baseUrl()}/v1/sessions`, { headers: await getHeaders() })
    if (!res.ok) throw new Error(`listSessions failed: HTTP ${res.status}`)
    return res.json() as Promise<SessionSummaryDto[]>
  },

  /** GET /v1/session/{id} — one session with its turn pointers. */
  async getSession(id: string): Promise<SessionDto> {
    const res = await fetch(`${baseUrl()}/v1/session/${encodeURIComponent(id)}`, {
      headers: await getHeaders(),
    })
    if (!res.ok) throw new Error(`getSession failed: HTTP ${res.status}`)
    return res.json() as Promise<SessionDto>
  },

  /** POST /v1/session/{id}/reset — snapshot + clear turns. */
  async resetSession(id: string): Promise<SessionDto> {
    const res = await fetch(`${baseUrl()}/v1/session/${encodeURIComponent(id)}/reset`, {
      method: 'POST',
      headers: await getHeaders(),
    })
    if (!res.ok) throw new Error(`resetSession failed: HTTP ${res.status}`)
    return res.json() as Promise<SessionDto>
  },

  /** GET /v1/session/{id}/turn/{turnId} — the stored envelope for one turn (history hydration). */
  async getSessionTurn(id: string, turnId: string): Promise<TurnEnvelopeDto> {
    const res = await fetch(
      `${baseUrl()}/v1/session/${encodeURIComponent(id)}/turn/${encodeURIComponent(turnId)}`,
      { headers: await getHeaders() },
    )
    if (!res.ok) throw new Error(`getSessionTurn failed: HTTP ${res.status}`)
    return res.json() as Promise<TurnEnvelopeDto>
  },

  /** POST /v1/session/{id}/undo — restore the latest snapshot (reset / edit_resend). */
  async undoSession(id: string): Promise<SessionDto> {
    const res = await fetch(`${baseUrl()}/v1/session/${encodeURIComponent(id)}/undo`, {
      method: 'POST',
      headers: await getHeaders(),
    })
    if (!res.ok) throw new Error(`undoSession failed: HTTP ${res.status}`)
    return res.json() as Promise<SessionDto>
  },

  /** POST /v1/action {edit_resend} — discard the turns after `fromTurnId` and
   *  re-run the edited question, streamed exactly like `streamTurn`. */
  async editResend(
    req: { sessionId: string; fromTurnId: string; editedQuestion: string; bubbleId?: string },
    handlers: StreamHandlers,
  ): Promise<void> {
    await postStream(
      '/v1/action',
      {
        sessionId: req.sessionId,
        bubbleId: req.bubbleId ?? '',
        action: {
          kind: 'edit_resend',
          payloadJson: JSON.stringify({
            editedQuestion: req.editedQuestion,
            fromTurnId: req.fromTurnId,
          }),
        },
      },
      handlers,
    )
  },

  /** POST /v1/action {kind, payloadJson} — the generic typed-action channel.
   *  Streamed exactly like a turn (step / envelope / error / done). The per-kind
   *  payload builders live in `services/typedAction.ts`; this is the one SSE
   *  consumer they (and `editResend`) share. `bubbleId` is required for the
   *  data-shaping / select_row kinds and optional for the rest. */
  async action(
    req: { sessionId: string; bubbleId?: string; action: { kind: string; payloadJson: string } },
    handlers: StreamHandlers,
  ): Promise<void> {
    await postStream(
      '/v1/action',
      { sessionId: req.sessionId, bubbleId: req.bubbleId ?? '', action: req.action },
      handlers,
    )
  },

  /** POST /v1/refresh[?service=] — metadata-refresh passthrough (`/refresh` slash). */
  async refresh(service?: string): Promise<RefreshResponseDto> {
    const qs = service ? `?service=${encodeURIComponent(service)}` : ''
    const res = await fetch(`${baseUrl()}/v1/refresh${qs}`, {
      method: 'POST',
      headers: await getHeaders(),
    })
    if (!res.ok) throw new Error(`refresh failed: HTTP ${res.status}`)
    return res.json() as Promise<RefreshResponseDto>
  },

  /** POST /v1/chat/stream — the streamed turn (step / envelope / error / done). */
  async streamTurn(req: ChatTurnRequestDto, handlers: StreamHandlers): Promise<void> {
    await postStream('/v1/chat/stream', req, handlers)
  },

  /** POST /v1/chat/resume — clarification resume (SSE, routed to the issuer). */
  async resumeClarification(req: ChatResumeRequestDto, handlers: StreamHandlers): Promise<void> {
    await postStream('/v1/chat/resume', req, handlers)
  },

  /** POST /v1/chat/turn — sync single-envelope turn (no SSE). Used by edit-resend
   *  / row-select, which want the terminal envelope directly. Returns null on a
   *  204 (no terminal) or a non-envelope (error) terminal arm. */
  async turn(req: ChatTurnRequestDto): Promise<FormatEnvelope | null> {
    const res = await fetch(`${baseUrl()}/v1/chat/turn`, {
      method: 'POST',
      headers: await getHeaders(),
      body: JSON.stringify(req),
    })
    if (res.status === 204) return null
    if (!res.ok) throw new Error(`turn failed: HTTP ${res.status}`)
    const event = (await res.json()) as Record<string, unknown>
    return event.envelope ? FormatEnvelope.fromJSON(event.envelope) : null
  },

  /** GET /ready — BFF readiness probe (not under /v1). */
  async ready(): Promise<boolean> {
    try {
      const res = await fetch(`${baseUrl()}/ready`, { headers: await getHeaders() })
      return res.ok
    } catch {
      return false
    }
  },
}
