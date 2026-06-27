// Iris Phase 4 Stage 4.2 — the artifacts client (pins, dashboards, refresh, open).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { artifactsApi } from '@/services/artifacts'

const fetchMock = vi.fn()

beforeEach(() => {
  setActivePinia(createPinia())
  fetchMock.mockReset()
  ;(globalThis as { fetch: typeof fetch }).fetch = fetchMock as unknown as typeof fetch
})

afterEach(() => vi.restoreAllMocks())

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function sse(frames: string[]) {
  return new Response(frames.join(''), {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

describe('artifactsApi', () => {
  it('createPin POSTs {kind:pin, turnId, bubbleId, name}', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ artifactId: 'a1', kind: 'pin', name: 'R', refreshMode: 'manual', memberIds: [], createdAt: 'now', updatedAt: 'now' }, 201))
    const out = await artifactsApi.createPin({ turnId: 't1', bubbleId: 'b1', name: 'R' })
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(String(url)).toMatch(/\/v1\/artifacts$/)
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toMatchObject({ kind: 'pin', turnId: 't1', bubbleId: 'b1', name: 'R' })
    expect(out.artifactId).toBe('a1')
  })

  it('createDashboard defaults refreshMode to on_open', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ artifactId: 'd1', kind: 'dashboard', name: 'B', refreshMode: 'on_open', memberIds: [], createdAt: 'now', updatedAt: 'now' }, 201))
    await artifactsApi.createDashboard({ name: 'B', memberIds: ['a1'] })
    const body = JSON.parse((fetchMock.mock.calls[0]![1] as RequestInit).body as string)
    expect(body).toMatchObject({ kind: 'dashboard', name: 'B', refreshMode: 'on_open', memberIds: ['a1'] })
  })

  it('list unwraps {artifacts}', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ artifacts: [{ artifactId: 'a1' }, { artifactId: 'a2' }] }))
    const out = await artifactsApi.list('pin')
    expect(String(fetchMock.mock.calls[0]![0])).toMatch(/\/v1\/artifacts\?kind=pin$/)
    expect(out.map((a) => a.artifactId)).toEqual(['a1', 'a2'])
  })

  it('refresh POSTs to /{id}/refresh and returns the updated artifact', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ artifactId: 'a1', refreshedAt: 'now', kind: 'pin', name: 'R', refreshMode: 'manual', memberIds: [], createdAt: 'now', updatedAt: 'now' }))
    const out = await artifactsApi.refresh('a1')
    expect(String(fetchMock.mock.calls[0]![0])).toMatch(/\/v1\/artifacts\/a1\/refresh$/)
    expect(out.refreshedAt).toBe('now')
  })

  it('openDashboard parses one pin frame per member', async () => {
    fetchMock.mockResolvedValue(
      sse([
        'event: pin\ndata: {"artifactId":"a1","name":"One"}\n\n',
        'event: pin\ndata: {"artifactId":"a2","name":"Two"}\n\n',
        'event: done\ndata: {}\n\n',
      ]),
    )
    const seen: string[] = []
    await artifactsApi.openDashboard('d1', (pin) => seen.push(pin.artifactId))
    expect(seen).toEqual(['a1', 'a2'])
  })

  it('remove tolerates a 404', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 404 }))
    await expect(artifactsApi.remove('gone')).resolves.toBeUndefined()
  })
})
