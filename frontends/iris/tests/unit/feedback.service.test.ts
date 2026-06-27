// Iris Phase 4 Stage 4.3 — feedback + discover clients.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { feedbackApi } from '@/services/feedback'
import { discoverApi } from '@/services/discover'

const fetchMock = vi.fn()

beforeEach(() => {
  setActivePinia(createPinia())
  fetchMock.mockReset()
  ;(globalThis as { fetch: typeof fetch }).fetch = fetchMock as unknown as typeof fetch
})
afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('feedbackApi', () => {
  it('POSTs verdict + reason to /v1/turns/{id}/feedback', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ turnId: 't1', verdict: 'down', reason: 'wrong_data' }))
    const out = await feedbackApi.submit('t1', 'down', 'wrong_data')
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(String(url)).toMatch(/\/v1\/turns\/t1\/feedback$/)
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ verdict: 'down', reason: 'wrong_data' })
    expect(out.verdict).toBe('down')
  })

  it('omits reason when not given', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ turnId: 't1', verdict: 'up' }))
    await feedbackApi.submit('t1', 'up')
    expect(JSON.parse((fetchMock.mock.calls[0]![1] as RequestInit).body as string)).toEqual({ verdict: 'up' })
  })
})

describe('discoverApi', () => {
  it('unwraps {domains}', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ domains: [{ agentId: 'golem-erp', displayName: 'ERP', blurb: '', exampleQuestions: [] }] }))
    const out = await discoverApi.domains()
    expect(String(fetchMock.mock.calls[0]![0])).toMatch(/\/v1\/discover$/)
    expect(out[0]!.agentId).toBe('golem-erp')
  })

  it('returns [] on a non-ok response (best-effort)', async () => {
    fetchMock.mockResolvedValue(new Response('nope', { status: 503 }))
    expect(await discoverApi.domains()).toEqual([])
  })

  it('returns [] when fetch throws', async () => {
    fetchMock.mockRejectedValue(new Error('network'))
    expect(await discoverApi.domains()).toEqual([])
  })
})
