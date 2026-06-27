// Unit coverage for the v2 → envelope/v1 normalisation transforms.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import {
  FormatEnvelope,
  FormatKind,
  PlanSource,
  normalizeEnvelopeJson,
  decodeV2Envelope,
} from '../src/index'

const fixturesDir = join(dirname(fileURLToPath(import.meta.url)), 'fixtures')
const load = (file: string) =>
  JSON.parse(readFileSync(join(fixturesDir, file), 'utf-8')) as Record<string, unknown>

describe('normalizeEnvelopeJson', () => {
  it('uppercases the plan_source enum string', () => {
    const n = normalizeEnvelopeJson(load('02-table-sorted-filtered.json'))
    expect(n.plan_source).toBe('AMEND')
  })

  it('uppercases format.kind', () => {
    const n = normalizeEnvelopeJson(load('01-table-basic.json')) as Record<string, unknown>
    expect((n.format as Record<string, unknown>).kind).toBe('TABLE')
  })

  it('lifts content (object) → content_json (string)', () => {
    const n = normalizeEnvelopeJson(load('01-table-basic.json'))
    expect(typeof n.content_json).toBe('string')
    expect(n).not.toHaveProperty('content')
    expect(JSON.parse(n.content_json as string)).toHaveLength(2)
  })

  it('renames table alternateColors → alternate_colors and stringifies filter values', () => {
    const n = normalizeEnvelopeJson(load('02-table-sorted-filtered.json')) as Record<string, unknown>
    const table = (n.format as Record<string, unknown>).table as Record<string, unknown>
    expect(table.alternate_colors).toBe('Rows')
    expect(table).not.toHaveProperty('alternateColors')
    const filters = table.filters as Array<Record<string, unknown>>
    expect(filters[0].value_json).toBe('1000')
    expect(filters[1].value_json).toBe('"Mléko"')
  })

  it('stringifies chart vega_lite_spec → vega_lite_spec_json and rows → rows_json', () => {
    const intent = normalizeEnvelopeJson(load('03-chart-intent.json')) as Record<string, unknown>
    const chart = (intent.format as Record<string, unknown>).chart as Record<string, unknown>
    expect(typeof chart.vega_lite_spec_json).toBe('string')
    expect(chart).not.toHaveProperty('vega_lite_spec')

    const loose = normalizeEnvelopeJson(load('04-chart-loose.json')) as Record<string, unknown>
    const looseChart = (loose.format as Record<string, unknown>).chart as Record<string, unknown>
    expect(typeof looseChart.rows_json).toBe('string')
  })

  it('wraps v2 chips into the PromptChip oneof arm with prefilled_args_json', () => {
    const n = normalizeEnvelopeJson(load('07-chips.json'))
    const chips = n.chips as Array<Record<string, unknown>>
    expect(chips).toHaveLength(4)
    const derived = chips[2].prompt as Record<string, unknown>
    expect(derived.source).toBe('pattern_derived')
    expect(derived.pattern_id).toBe('product-detail')
    expect(JSON.parse(derived.prefilled_args_json as string)).toEqual({ product: 'Mléko 1l' })
  })

  it('moves current_view.args → args_json', () => {
    const n = normalizeEnvelopeJson(load('11-entity-context-view.json')) as Record<string, unknown>
    const cv = n.current_view as Record<string, unknown>
    expect(typeof cv.args_json).toBe('string')
    expect(cv).not.toHaveProperty('args')
    expect(cv.total_rows).toBe(12)
  })

  it('is pure — does not mutate the input', () => {
    const raw = load('01-table-basic.json')
    const before = JSON.stringify(raw)
    normalizeEnvelopeJson(raw)
    expect(JSON.stringify(raw)).toBe(before)
  })

  it('decodeV2Envelope yields a typed envelope with mapped enums', () => {
    const env: FormatEnvelope = decodeV2Envelope(load('06-plaintext.json'))
    expect(env.format?.kind).toBe(FormatKind.PLAINTEXT)
    expect(env.planSource).toBe(PlanSource.PATTERN)
    expect(env.text).toContain('aktivních')
  })

  it('encodes an explicit null filter value as "null" (not dropped)', () => {
    // Rule-7: an explicit JSON null IS a value → "null"; only undefined is absent.
    const n = normalizeEnvelopeJson({
      format: {
        kind: 'table',
        table: { filters: [{ column: 'note', operator: 'eq', value: null }] },
      },
    }) as Record<string, unknown>
    const table = (n.format as Record<string, unknown>).table as Record<string, unknown>
    const filters = table.filters as Array<Record<string, unknown>>
    expect(filters[0].value_json).toBe('null')
    expect(JSON.parse(filters[0].value_json as string)).toBeNull()
  })

  it('round-trips falsy filter values through value_json', () => {
    const filtersFor = (value: unknown) => {
      const n = normalizeEnvelopeJson({
        format: { kind: 'table', table: { filters: [{ column: 'c', operator: 'eq', value }] } },
      }) as Record<string, unknown>
      const table = (n.format as Record<string, unknown>).table as Record<string, unknown>
      return (table.filters as Array<Record<string, unknown>>)[0].value_json
    }
    expect(filtersFor(false)).toBe('false')
    expect(filtersFor(0)).toBe('0')
    expect(filtersFor('')).toBe('""')
  })

  it('normalizes per-kind table details nested under format.details (legacy)', () => {
    // Older shape: details under format.details with format.kind set.
    const nested = {
      format: {
        kind: 'table',
        details: { headers: [{ name: 'qty', title: 'Množství' }], alternateColors: 'Rows' },
      },
    }
    const n = normalizeEnvelopeJson(nested) as Record<string, unknown>
    const table = (n.format as Record<string, unknown>).table as Record<string, unknown>
    expect(table.alternate_colors).toBe('Rows')
    expect(table).not.toHaveProperty('alternateColors')
    expect((table.headers as unknown[]).length).toBe(1)
  })

  it('normalizes a markdown envelope (format.kind: markdown) and survives decode', () => {
    const env = decodeV2Envelope(load('05-markdown.json'))
    expect(env.format?.kind).toBe(FormatKind.MARKDOWN)
    expect(env.format?.markdown).toBeDefined()
    expect(env.format?.markdown?.allowMermaid).toBe(true)
    expect(env.format?.markdown?.allowImages).toBe(false)
  })

  it('decodeV2Envelope reads the snake_case filter value into the typed valueJson field', () => {
    // Proves the typed fromJSON snake_case branch — not just the intermediate JSON.
    const env = decodeV2Envelope(load('02-table-sorted-filtered.json'))
    const filters = env.format?.table?.filters ?? []
    const czech = filters.find((f) => f.column === 'product')
    expect(czech?.valueJson).toBe('"Mléko"')
  })
})
