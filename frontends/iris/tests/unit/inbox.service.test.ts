// Iris Phase 4 Stage 4.1 — inbox client (view + SSE stream).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { inboxApi } from '@/services/inbox'

const fetchMock = vi.fn()

beforeEach(() => {
  setActivePinia(createPinia())
  fetchMock.mockReset()
  ;(globalThis as { fetch: typeof fetch }).fetch = fetchMock as unknown as typeof fetch
})
afterEach(() => vi.restoreAllMocks())

describe('inboxApi.view', () => {
  it('returns the aggregated view', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ items: [{ investigationId: 'i1' }], counts: { running: 1, needsInput: 0 } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    const v = await inboxApi.view()
    expect(String(fetchMock.mock.calls[0]![0])).toMatch(/\/v1\/inbox$/)
    expect(v.counts.running).toBe(1)
  })

  it('returns an empty view on failure', async () => {
    fetchMock.mockResolvedValue(new Response('x', { status: 500 }))
    expect((await inboxApi.view()).items).toEqual([])
  })
})

describe('inboxApi.stream', () => {
  it('parses inbox_event frames and ignores others', async () => {
    const body = [
      'event: inbox_event\ndata: {"items":[],"counts":{"running":2,"needsInput":1}}\n\n',
      ': heartbeat\n\n',
      'event: inbox_event\ndata: {"items":[{"investigationId":"i9"}],"counts":{"running":1,"needsInput":0}}\n\n',
    ].join('')
    fetchMock.mockResolvedValue(new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))

    const views: number[] = []
    inboxApi.stream((v) => views.push(v.counts.running))
    // allow the async reader to drain
    await new Promise((r) => setTimeout(r, 20))
    expect(views).toEqual([2, 1])
  })

  it('flushes a final frame that lacks a trailing blank line', async () => {
    // No "\n\n" after the last data: line — the frame must still be delivered.
    const body = 'event: inbox_event\ndata: {"items":[],"counts":{"running":7,"needsInput":0}}\n'
    fetchMock.mockResolvedValue(new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))

    const views: number[] = []
    inboxApi.stream((v) => views.push(v.counts.running))
    await new Promise((r) => setTimeout(r, 20))
    expect(views).toEqual([7])
  })

  it('does not read the body and signals onFail on a non-ok response', async () => {
    fetchMock.mockResolvedValue(new Response('not a stream', { status: 503 }))
    const onView = vi.fn()
    const onFail = vi.fn()
    inboxApi.stream(onView, onFail)
    await new Promise((r) => setTimeout(r, 20))
    expect(onView).not.toHaveBeenCalled()
    expect(onFail).toHaveBeenCalledTimes(1)
  })

  it('signals onFail when the stream ends without a clean close', async () => {
    const body = 'event: inbox_event\ndata: {"items":[],"counts":{"running":1,"needsInput":0}}\n\n'
    fetchMock.mockResolvedValue(new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
    const onFail = vi.fn()
    inboxApi.stream(() => {}, onFail)
    await new Promise((r) => setTimeout(r, 20))
    expect(onFail).toHaveBeenCalledTimes(1)
  })
})
