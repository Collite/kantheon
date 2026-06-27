// Phase 2 Stage 2.2 — the iris-bff client. Targets the BFF /v1/* REST + SSE
// surface (contracts §2); camelCase DTOs; bearer (OBO) on every call.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { irisStream } from '@/services/irisStream'
import { useAuthStore } from '@/stores/auth'

const fetchMock = vi.fn()

beforeEach(() => {
  setActivePinia(createPinia())
  fetchMock.mockReset()
  ;(globalThis as { fetch: typeof fetch }).fetch = fetchMock as unknown as typeof fetch
})

afterEach(() => {
  vi.restoreAllMocks()
})

function jsonResponse(body: unknown, init: ResponseInit = { status: 200 }) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init.headers },
  })
}

function sseResponse(frames: string[][]) {
  // Each frame is a list of lines; frames are joined by a blank line.
  const body = frames.map((lines) => lines.join('\n')).join('\n\n') + '\n\n'
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

describe('irisStream.createSession', () => {
  it('POSTs /v1/session and returns the SessionDto', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        sessionId: 's-1', userId: 'u', tenantId: 't', turns: [],
        createdAt: 'now', updatedAt: 'now',
        staticChips: [{ display: 'X', prompt: 'X', source: 'static' }],
        exampleQuestions: ['Foo'], packages: ['ucetnictvi'], agentVersion: 'golem-v2.0.0',
      }),
    )

    const out = await irisStream.createSession()

    const [url, init] = fetchMock.mock.calls[0] as unknown as [URL | string, RequestInit]
    expect(String(url)).toMatch(/\/v1\/session$/)
    expect(init.method).toBe('POST')
    expect(out.sessionId).toBe('s-1')
    expect(out.staticChips[0]?.prompt).toBe('X')
    expect(out.packages).toEqual(['ucetnictvi'])
  })
})

describe('irisStream.refresh', () => {
  it('POSTs /v1/refresh with no query when no service is given', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ results: [{ service: 'metadata', status: 'ok', version: 'v42' }] }))

    const out = await irisStream.refresh()

    const [url, init] = fetchMock.mock.calls[0] as unknown as [URL | string, RequestInit]
    expect(String(url)).toMatch(/\/v1\/refresh$/)
    expect(init.method).toBe('POST')
    expect(out.results[0]?.service).toBe('metadata')
  })

  it('passes the named service as a query param', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ results: [] }))
    await irisStream.refresh('theseus')
    const [url] = fetchMock.mock.calls[0] as unknown as [URL | string, RequestInit]
    expect(String(url)).toMatch(/\/v1\/refresh\?service=theseus$/)
  })

  it('throws on a non-OK response', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ errorCode: 'refresh_unavailable', message: 'no' }, { status: 503 }))
    await expect(irisStream.refresh()).rejects.toThrow(/refresh failed/)
  })
})

describe('irisStream.turn', () => {
  it('POSTs /v1/chat/turn and decodes the terminal envelope arm', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        turnId: 't',
        envelope: {
          bubbleId: 'b', turnId: 't', threadId: 'th',
          format: { kind: 'TABLE' }, planSource: 'PATTERN',
          createdAt: 'now', agentVersion: 'g',
        },
      }),
    )

    const env = await irisStream.turn({ sessionId: 's-1', question: 'hi' })
    const [url, init] = fetchMock.mock.calls[0] as unknown as [URL | string, RequestInit]
    expect(String(url)).toMatch(/\/v1\/chat\/turn$/)
    expect(JSON.parse(init.body as string)).toEqual({ sessionId: 's-1', question: 'hi' })
    expect(env?.bubbleId).toBe('b')
  })

  it('returns null on a 204 (no terminal envelope)', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    expect(await irisStream.turn({ sessionId: 's-1', question: 'hi' })).toBeNull()
  })
})

describe('irisStream auth headers', () => {
  function headersOf(call: unknown): Record<string, string> {
    const [, init] = call as [URL | string, RequestInit]
    return init.headers as Record<string, string>
  }

  it('attaches the Keycloak bearer + X-User-ID (incl. the SSE stream)', async () => {
    const auth = useAuthStore()
    auth.isAuthenticated = true
    auth.token = 'jwt-abc'
    auth.fallbackUserId = 'u-1'

    fetchMock.mockResolvedValue(jsonResponse({
      sessionId: 's', userId: 'u', tenantId: 't', turns: [], createdAt: 'n', updatedAt: 'n',
      staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
    }))
    await irisStream.createSession()
    const sync = headersOf(fetchMock.mock.calls[0])
    expect(sync['Authorization']).toBe('Bearer jwt-abc')
    expect(sync['X-User-ID']).toBe('u-1')

    // The fetch-based SSE stream carries the same bearer + the SSE Accept.
    fetchMock.mockResolvedValue(sseResponse([['event: done', 'data: {"turnId":"t","done":{"outcome":"done"}}']]))
    await irisStream.streamTurn({ sessionId: 's', question: 'hi' }, {})
    const stream = headersOf(fetchMock.mock.calls[1])
    expect(stream['Authorization']).toBe('Bearer jwt-abc')
    expect(stream['Accept']).toBe('text/event-stream')
  })

  it('omits the Authorization header when not authenticated', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      sessionId: 's', userId: 'u', tenantId: 't', turns: [], createdAt: 'n', updatedAt: 'n',
      staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
    }))
    await irisStream.createSession()
    expect(headersOf(fetchMock.mock.calls[0])['Authorization']).toBeUndefined()
  })
})

describe('irisStream.streamTurn (SSE)', () => {
  it('dispatches step / envelope / done arms from the IrisStreamEvent stream', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        ['event: step', 'data: {"turnId":"t","step":{"node":"execute","phase":"started"}}'],
        ['event: step', 'data: {"turnId":"t","step":{"node":"pick_plan","detailJson":"{\\"source\\":\\"regex\\",\\"patternId\\":\\"p\\",\\"score\\":0.97}"}}'],
        ['event: envelope', 'data: {"turnId":"t","envelope":{"bubbleId":"b","turnId":"t","threadId":"th","format":{"kind":"TABLE"},"planSource":"PATTERN","createdAt":"now","agentVersion":"g"}}'],
        ['event: done', 'data: {"turnId":"t","done":{"outcome":"done"}}'],
      ]),
    )

    const steps: string[] = []
    let envelopeBubble: string | undefined
    let doneOutcome: string | undefined

    await irisStream.streamTurn(
      { sessionId: 'th', question: 'x' },
      {
        onStep: (s) => steps.push(s.node),
        onEnvelope: (e) => { envelopeBubble = e.bubbleId },
        onDone: (d) => { doneOutcome = d.outcome },
      },
    )

    expect(steps).toEqual(['execute', 'pick_plan'])
    expect(envelopeBubble).toBe('b')
    expect(doneOutcome).toBe('done')
  })

  it('routes the error arm through onError', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([['event: error', 'data: {"turnId":"t","error":{"code":"BOOM","message":"oh no"}}']]),
    )

    let errCode: string | undefined
    await irisStream.streamTurn(
      { sessionId: 'th', question: 'x' },
      { onError: (e) => { errCode = e.code } },
    )

    expect(errCode).toBe('BOOM')
  })
})

describe('irisStream.getSessionTurn / undoSession (Stage 2.3)', () => {
  it('GETs /v1/session/{id}/turn/{turnId} and returns the TurnEnvelopeDto', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ turnId: 'tn-1', agentId: 'golem-v2', status: 'done', envelope: { bubbleId: 'b' } }),
    )
    const out = await irisStream.getSessionTurn('s-1', 'tn-1')
    const [url] = fetchMock.mock.calls[0] as unknown as [URL | string, RequestInit]
    expect(String(url)).toMatch(/\/v1\/session\/s-1\/turn\/tn-1$/)
    expect(out.turnId).toBe('tn-1')
  })

  it('POSTs /v1/session/{id}/undo and returns the restored SessionDto', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        sessionId: 's-1', userId: 'u', tenantId: 't', turns: [], createdAt: 'n', updatedAt: 'n',
        staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
      }),
    )
    const out = await irisStream.undoSession('s-1')
    const [url, init] = fetchMock.mock.calls[0] as unknown as [URL | string, RequestInit]
    expect(String(url)).toMatch(/\/v1\/session\/s-1\/undo$/)
    expect(init.method).toBe('POST')
    expect(out.sessionId).toBe('s-1')
  })

  it('undoSession throws on a 409 (nothing to undo)', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ errorCode: 'nothing_to_undo', message: 'no' }, { status: 409 }))
    await expect(irisStream.undoSession('s-1')).rejects.toThrow(/undoSession failed/)
  })
})

describe('irisStream.editResend (POST /v1/action, Stage 2.3)', () => {
  it('streams the re-run with an edit_resend TypedActionRequest body', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        ['event: envelope', 'data: {"turnId":"t2","envelope":{"bubbleId":"b2","turnId":"t2","threadId":"th","format":{"kind":"TABLE"},"planSource":"PATTERN","createdAt":"now","agentVersion":"g"}}'],
        ['event: done', 'data: {"turnId":"t2","done":{"outcome":"done"}}'],
      ]),
    )
    let bubble: string | undefined
    let seenTurnId: string | undefined
    await irisStream.editResend(
      { sessionId: 's-1', fromTurnId: 'turn-1', editedQuestion: 'edited?' },
      { onEnvelope: (e) => { bubble = e.bubbleId }, onTurnId: (id) => { seenTurnId = id } },
    )

    const [url, init] = fetchMock.mock.calls[0] as unknown as [URL | string, RequestInit]
    expect(String(url)).toMatch(/\/v1\/action$/)
    const body = JSON.parse(init.body as string)
    expect(body.action.kind).toBe('edit_resend')
    expect(JSON.parse(body.action.payloadJson)).toEqual({ editedQuestion: 'edited?', fromTurnId: 'turn-1' })
    expect(bubble).toBe('b2')
    // onTurnId surfaces the server turn id from the first event that carries it.
    expect(seenTurnId).toBe('t2')
  })
})

describe('irisStream.ready', () => {
  it('returns true on a 200 from /ready', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 'UP' }))
    expect(await irisStream.ready()).toBe(true)
    const [url] = fetchMock.mock.calls[0] as unknown as [URL | string, RequestInit]
    expect(String(url)).toMatch(/\/ready$/)
  })

  it('returns false on a non-OK / thrown fetch', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 503 }))
    expect(await irisStream.ready()).toBe(false)
  })
})
