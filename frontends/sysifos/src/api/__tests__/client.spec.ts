import { describe, it, expect, vi, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { bff, BffError } from '../client'
import { useSessionStore } from '@/stores/session'
import { devBearer } from '@/utils/jwt'

function withSession() {
  setActivePinia(createPinia())
  useSessionStore().setFromToken(devBearer('u1', 'acme'))
}

afterEach(() => vi.unstubAllGlobals())

describe('bff()', () => {
  it('attaches the bearer, builds the query string, and parses JSON', async () => {
    withSession()
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ clients: [] }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const out = await bff('/midas/clients', { query: { page: 0, status: 'CLIENT_ACTIVE', empty: '' } })
    expect(out).toEqual({ clients: [] })

    const [url, opts] = fetchMock.mock.calls[0]!
    expect(url).toContain('/bff/midas/clients?')
    expect(url).toContain('status=CLIENT_ACTIVE')
    expect(url).not.toContain('empty=') // empty params are dropped
    expect((opts.headers as Record<string, string>).Authorization).toMatch(/^Bearer /)
  })

  it('throws BffError on a non-2xx', async () => {
    withSession()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"code":"VALIDATION_FAILED"}', { status: 400 })))
    await expect(bff('/midas/clients', { method: 'POST', body: {} })).rejects.toBeInstanceOf(BffError)
  })
})
