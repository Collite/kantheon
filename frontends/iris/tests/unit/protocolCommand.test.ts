// PT P3·S3.1 T5 — `/protocol`: argument parsing, the promote flow, and the
// panel download.
//
// The parse table's negative cases carry the weight: an invalid argument must
// send NOTHING. Coercing `/protocol 3.5` into `last` would hand the user a
// document about a different scope than they asked for — on a surface whose
// entire purpose is to be trusted about what happened.
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { parseProtocolArg, protocolScopeSlug, SLASH_COMMANDS } from '@/components/chat/slashCommands'
import { protocolFilename, slugify } from '@/components/tabs/downloadMarkdown'

describe('/protocol — registry entry', () => {
  it('is registered as a request command that accepts an argument', () => {
    const spec = SLASH_COMMANDS.find((c) => c.name === 'protocol')
    expect(spec).toBeDefined()
    expect(spec?.kind).toBe('request')
    expect(spec?.acceptsArg).toBe(true)
    expect(spec?.argHint).toBe('[session|N]')
    expect(spec?.descriptionKey).toBe('slash.protocol')
  })

  it('has en and cs descriptions', async () => {
    const en = (await import('@/i18n/en.json')).default as Record<string, Record<string, string>>
    const cs = (await import('@/i18n/cs.json')).default as Record<string, Record<string, string>>
    for (const k of ['protocol', 'protocolUsage', 'protocolBadScope', 'protocolForbidden', 'protocolNoSession', 'protocolFailed']) {
      expect(en.slash[k], `en.slash.${k}`).toBeTruthy()
      expect(cs.slash[k], `cs.slash.${k}`).toBeTruthy()
    }
  })
})

describe('/protocol — argument parse table', () => {
  it.each([
    ['', 'last'],
    ['   ', 'last'],
    ['session', 'session'],
    ['SESSION', 'session'],
    [' session ', 'session'],
    ['last', 'last'],
    ['LAST', 'last'],
  ])('%j -> %j', (raw, expected) => {
    expect(parseProtocolArg(raw)).toBe(expected)
  })

  it.each([
    ['3', 3],
    ['12', 12],
    ['1', 1],
  ])('%j -> lastN %i', (raw, n) => {
    expect(parseProtocolArg(raw)).toEqual({ lastN: n })
  })

  it.each([
    ['0', 'zero is a mistake, not a small scope'],
    ['-1', 'negative'],
    ['abc', 'not a number'],
    ['3.5', 'decimal'],
    ['03', 'leading zero'],
    ['3x', 'trailing junk'],
    ['session 3', 'two arguments'],
    ['lastly', 'near-miss of a keyword'],
  ])('%j -> invalid (%s)', (raw) => {
    expect(parseProtocolArg(raw)).toBeNull()
  })

  it('undefined (no argument at all) is the last-turn default', () => {
    expect(parseProtocolArg(undefined)).toBe('last')
  })
})

describe('/protocol — filename', () => {
  it.each([
    ['last' as const, 'last'],
    ['session' as const, 'session'],
  ])('scope %j slugs to %j', (scope, slug) => {
    expect(protocolScopeSlug(scope)).toBe(slug)
  })

  it('lastN slugs to last-<N>', () => {
    expect(protocolScopeSlug({ lastN: 3 })).toBe('last-3')
  })

  it('builds protocol-<sessionId>-<scope>.md', () => {
    expect(protocolFilename('abc-123', 'last-3')).toBe('protocol-abc-123-last-3.md')
  })

  it('slugify gives a safe fallback stem for panels that do not name themselves', () => {
    expect(slugify('Protocol — Q3 margin — whole session')).toBe('protocol-q3-margin-whole-session')
    expect(slugify('!!!')).toBe('panel')
  })
})

describe('/protocol — service call shape', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('POSTs the scope verbatim and returns the parsed body', async () => {
    vi.doMock('@/services/authHeaders', () => ({ authHeaders: async () => ({ 'X-User-ID': 'maya' }) }))
    const { requestProtocol } = await import('@/services/protocol')

    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ protocolId: 'p1', title: 'Protocol — x — last', envelope: { text: '# doc' } }),
    })

    const out = await requestProtocol('sess-1', { lastN: 3 })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/v1/session/sess-1/protocol')
    expect(init.method).toBe('POST')
    // The wire shape the BFF parses (contracts §3.1).
    expect(JSON.parse(init.body)).toEqual({ scope: { lastN: 3 } })
    expect(out.protocolId).toBe('p1')
  })

  it.each([400, 403, 404, 500])('HTTP %i throws with the status attached so the caller can toast', async (status) => {
    vi.doMock('@/services/authHeaders', () => ({ authHeaders: async () => ({}) }))
    const { requestProtocol, ProtocolRequestError } = await import('@/services/protocol')

    fetchMock.mockResolvedValue({ ok: false, status, json: async () => ({}) })

    await expect(requestProtocol('sess-1', 'last')).rejects.toBeInstanceOf(ProtocolRequestError)
    await expect(requestProtocol('sess-1', 'last')).rejects.toMatchObject({ status })
  })
})

describe('/protocol — panel download', () => {
  it('writes a Blob of the markdown byte-for-byte and names the file', async () => {
    const { downloadMarkdown } = await import('@/components/tabs/downloadMarkdown')

    const markdown = '# Protocol\n\nline two\n'
    const captured: { name?: string; blobText?: string } = {}

    const created: string[] = []
    vi.stubGlobal('URL', {
      createObjectURL: (b: Blob) => {
        // Read the blob's bytes so "byte-for-byte" is asserted, not assumed.
        captured.blobText = (b as unknown as { __text?: string }).__text
        created.push('blob:x')
        return 'blob:x'
      },
      revokeObjectURL: vi.fn(),
    })
    // jsdom's Blob has no sync text(); capture the constructor input instead.
    const RealBlob = globalThis.Blob
    vi.stubGlobal(
      'Blob',
      class extends RealBlob {
        __text: string
        constructor(parts: BlobPart[], opts?: BlobPropertyBag) {
          super(parts, opts)
          this.__text = parts.join('')
        }
      },
    )

    const clickSpy = vi.fn()
    const realCreate = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = realCreate(tag)
      if (tag === 'a') {
        Object.defineProperty(el, 'click', { value: clickSpy })
        Object.defineProperty(el, 'download', {
          set: (v: string) => {
            captured.name = v
          },
          get: () => captured.name,
          configurable: true,
        })
      }
      return el
    })

    downloadMarkdown(markdown, 'protocol-s1-last.md')

    expect(clickSpy).toHaveBeenCalledTimes(1)
    expect(captured.name).toBe('protocol-s1-last.md')
    // No BOM, no re-serialisation — exactly what the server rendered.
    expect(captured.blobText).toBe(markdown)
    expect(captured.blobText?.startsWith('﻿')).toBe(false)

    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })
})
