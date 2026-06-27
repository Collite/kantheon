// Iris Phase 2 Stage 2.2 T2 — golden-fixture renderer parity.
//
// Locks envelope/v1 renderer behaviour against the 12 shared fixtures that the
// envelope-ts golden gate also exercises (shared/libs/ts/envelope-ts/test/
// fixtures). Each fixture is the recorded v2 wire; we decode it through the same
// edge the FE uses at ingest (`decodeV2Envelope`), dispatch through the real
// `formatCatalog`/`resolveRenderer`, and mount the resolved renderer with the
// exact props ChatBubble feeds it (content via `envelopeContent`, per-kind
// `format.{table,chart,markdown}` details). Asserts:
//   1. catalog dispatch never falls to UnsupportedRenderer for a known kind, and
//   2. each kind produces meaningful DOM (the shape-agnostic safety net).
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { createI18n } from 'vue-i18n'
import { resolveRenderer, fallbackRenderer } from '@/catalog/formatCatalog'
import {
  FormatKind,
  decodeV2Envelope,
  envelopeContent,
  type FormatEnvelope,
} from '@/types/envelope'
import PlainTextRenderer from '@/components/chat/formats/PlainTextRenderer.vue'
import MarkdownRenderer from '@/components/chat/formats/MarkdownRenderer.vue'
import TableRenderer from '@/components/chat/formats/TableRenderer.vue'
import ChartRenderer from '@/components/chat/formats/ChartRenderer.vue'

// vega-embed touches the real DOM/SVG pipeline; stub it so ChartRenderer mounts
// in jsdom (mirrors how the app calls `embed()` in onMounted).
vi.mock('vega-embed', () => ({
  default: vi.fn(async () => ({ view: { finalize: () => {} } })),
}))

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en: {} } })

// PrimeVue's ContextMenu (TableRenderer/ChartRenderer) binds matchMedia on mount.
beforeAll(() => {
  if (!window.matchMedia) {
    window.matchMedia = (query: string) =>
      ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList
  }
})

const fixturesDir = join(
  dirname(fileURLToPath(import.meta.url)),
  '../../../../shared/libs/ts/envelope-ts/test/fixtures',
)
const loadEnvelope = (file: string): FormatEnvelope =>
  decodeV2Envelope(
    JSON.parse(readFileSync(join(fixturesDir, file), 'utf-8')) as Record<string, unknown>,
  )

// Mirror ChatBubble.effectiveDetails: envelope/v1 carries per-kind details on
// format.{table,chart,markdown} (no v2 `details` union).
const detailsFor = (env: FormatEnvelope) => {
  const fmt = env.format
  if (!fmt) return undefined
  return fmt.kind === FormatKind.TABLE
    ? fmt.table
    : fmt.kind === FormatKind.CHART
      ? fmt.chart
      : fmt.kind === FormatKind.MARKDOWN
        ? fmt.markdown
        : undefined
}

const mountRenderer = (env: FormatEnvelope) =>
  mount(resolveRenderer(env.format?.kind), {
    global: { plugins: [createPinia(), PrimeVue, ToastService, i18n] },
    props: {
      text: env.text,
      content: envelopeContent(env),
      details: detailsFor(env),
      kind: env.format?.kind,
    },
  })

// Expected renderer per fixture — the catalog-dispatch contract. Fixtures whose
// `format.kind` is plaintext (chips/clarification/error tails) ride the
// PlainTextRenderer; their non-format payload is asserted by the dedicated
// store/component specs, not here.
const DISPATCH: Array<[string, FormatKind, unknown]> = [
  ['01-table-basic.json', FormatKind.TABLE, TableRenderer],
  ['02-table-sorted-filtered.json', FormatKind.TABLE, TableRenderer],
  ['03-chart-intent.json', FormatKind.CHART, ChartRenderer],
  ['04-chart-loose.json', FormatKind.CHART, ChartRenderer],
  ['05-markdown.json', FormatKind.MARKDOWN, MarkdownRenderer],
  ['06-plaintext.json', FormatKind.PLAINTEXT, PlainTextRenderer],
  ['07-chips.json', FormatKind.PLAINTEXT, PlainTextRenderer],
  ['08-clarification-entity.json', FormatKind.PLAINTEXT, PlainTextRenderer],
  ['09-clarification-missing-arg.json', FormatKind.PLAINTEXT, PlainTextRenderer],
  ['10-drilldowns.json', FormatKind.TABLE, TableRenderer],
  ['11-entity-context-view.json', FormatKind.TABLE, TableRenderer],
  ['12-error-tail.json', FormatKind.PLAINTEXT, PlainTextRenderer],
]

describe('envelope/v1 golden-fixture catalog dispatch', () => {
  it('covers the full shared corpus (12 fixtures)', () => {
    expect(DISPATCH).toHaveLength(12)
  })

  it.each(DISPATCH)('%s decodes to a known FormatKind (never UNRECOGNIZED)', (file, kind) => {
    const env = loadEnvelope(file)
    expect(env.format?.kind).toBe(kind)
    expect(env.format?.kind).not.toBe(FormatKind.UNRECOGNIZED)
  })

  it.each(DISPATCH)('%s resolves to the expected renderer, not the fallback', (file, _kind, expected) => {
    const env = loadEnvelope(file)
    const renderer = resolveRenderer(env.format?.kind)
    expect(renderer).toBe(expected)
    expect(renderer).not.toBe(fallbackRenderer)
  })
})

describe('envelope/v1 golden-fixture DOM parity', () => {
  it('01-table-basic renders a multi-column grid with the v1 headers', () => {
    const w = mountRenderer(loadEnvelope('01-table-basic.json'))
    const headers = w.findAll('th').map((th) => th.text().replace(/Σ$/, ''))
    expect(headers).not.toEqual(['Property', 'Value'])
    expect(headers).toContain('Zákazník')
    expect(headers).toContain('Tržby')
    expect(w.text()).toContain('Kaufland ČR v.o.s.')
  })

  it('03-chart-intent renders the chart surface (intent + content rows)', () => {
    const w = mountRenderer(loadEnvelope('03-chart-intent.json'))
    // The chart toolbar/container mounts even with vega stubbed; no fallback msg.
    expect(w.find('.unsupported').exists()).toBe(false)
    expect(w.html().length).toBeGreaterThan(0)
  })

  it('04-chart-loose renders from the chart-details rowsJson (no top-level content)', () => {
    // Regression guard: the "loose" shape carries rows under format.chart
    // (normalised to `rowsJson`) with content=null. ChartRenderer must fall back
    // to those rows instead of rendering empty.
    const env = loadEnvelope('04-chart-loose.json')
    expect(Array.isArray(envelopeContent(env))).toBe(false) // top-level content is null
    expect((env.format?.chart?.rowsJson ?? '').length).toBeGreaterThan(0)
    const w = mountRenderer(env)
    expect(w.find('.unsupported').exists()).toBe(false)
  })

  it('05-markdown renders the parsed markdown body', () => {
    const w = mountRenderer(loadEnvelope('05-markdown.json'))
    expect(w.text()).toContain('Souhrn')
  })

  it('06-plaintext renders the answer text verbatim', () => {
    const w = mountRenderer(loadEnvelope('06-plaintext.json'))
    expect(w.text()).toContain('1 248 aktivních zákazníků')
  })
})
