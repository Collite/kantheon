// SQL-result rendering contract: an ARRAY `content` renders a real multi-column
// grid, and `details` (TableDetails on format.table) drives ordered headers +
// numeric right-alignment. Guards the fix for the "Property/Value" 2-row bug,
// where a {columns, rows} object content fell into the key/value branch.
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { createI18n } from 'vue-i18n'
import TableRenderer from '@/components/chat/formats/TableRenderer.vue'
import { FormatKind } from '@/types/envelope'
import type { TableDetails } from '@/types/envelope'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en: {} } })

// PrimeVue's ContextMenu binds a matchMedia listener on mount; jsdom has no
// matchMedia, so stub a no-op MediaQueryList.
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

// `details` is loosely typed: fixtures exercise the FE-local number descriptor
// (envelope/v1 TableColumnSpec dropped NumberFormatSpec; the renderer reads it
// via a local cast). Cast at the prop boundary.
function mountTable(content: unknown, details?: unknown) {
  return mount(TableRenderer, {
    global: { plugins: [createPinia(), PrimeVue, ToastService, i18n] },
    props: { content, details: details as TableDetails | undefined, kind: FormatKind.TABLE },
  })
}

const ROWS = [
  { IDUCETZAP: 11, NAZEV_STR: 'X', UCETNI_HODNOTA: 1.5 },
  { IDUCETZAP: 12, NAZEV_STR: 'Y', UCETNI_HODNOTA: -2.0 },
]

const DETAILS = {
  headers: [
    { name: 'IDUCETZAP', title: 'IDUCETZAP' },
    { name: 'NAZEV_STR', title: 'NAZEV_STR' },
    { name: 'UCETNI_HODNOTA', title: 'UCETNI_HODNOTA' },
  ],
  columns: {
    IDUCETZAP: { alignment: 'right' },
    UCETNI_HODNOTA: { alignment: 'right', format: '%.2f' },
  },
}

describe('TableRenderer', () => {
  it('renders array content as a multi-column grid, NOT a Property/Value table', () => {
    const w = mountTable(ROWS, DETAILS)
    // Numeric headers carry a trailing Σ (sum) affordance — strip it to read titles.
    const headerText = w.findAll('th').map((th) => th.text().replace(/Σ$/, ''))
    // The buggy path produced exactly ['Property', 'Value']; the real grid uses
    // the data columns in the order given by details.headers.
    expect(headerText).not.toEqual(['Property', 'Value'])
    expect(headerText).toContain('IDUCETZAP')
    expect(headerText).toContain('NAZEV_STR')
    expect(headerText).toContain('UCETNI_HODNOTA')
    expect(headerText.indexOf('IDUCETZAP')).toBeLessThan(headerText.indexOf('NAZEV_STR'))
  })

  it('derives columns from row keys when no details are supplied', () => {
    const w = mountTable(ROWS)
    const headerText = w.findAll('th').map((th) => th.text().replace(/Σ$/, ''))
    expect(headerText).not.toEqual(['Property', 'Value'])
    expect(headerText).toContain('UCETNI_HODNOTA')
  })

  it('right-aligns numeric columns with a full-width cell span (so text-align applies)', () => {
    const w = mountTable(ROWS, DETAILS)
    // Body cell for a right-aligned column must carry both classes — `cell-content`
    // (display:inline-block; width:100%) is what makes `text-right` visible.
    const cells = w.findAll('td .cell-content.text-right')
    expect(cells.length).toBeGreaterThan(0)
    const html = cells.map((c) => c.classes()).flat()
    expect(html).toContain('cell-content')
    expect(html).toContain('text-right')
  })

  it('rounds float columns to 2 decimals via the %.2f format spec', () => {
    const w = mountTable([{ UCETNI_HODNOTA: 4266.400000000001 }], {
      headers: [{ name: 'UCETNI_HODNOTA', title: 'UCETNI_HODNOTA' }],
      columns: { UCETNI_HODNOTA: { alignment: 'right', format: '%.2f' } },
    })
    const body = w.find('td .cell-content')
    expect(body.text()).toBe('4266.40')
  })

  it('still renders a plain object as a Property/Value table', () => {
    const w = mountTable({ row_count: 2, status: 'ok' })
    const headerText = w.findAll('th').map((th) => th.text())
    expect(headerText).toEqual(['Property', 'Value'])
  })
})

describe('TableRenderer — locale-aware `number` descriptor', () => {
  const numberCol = (number: Record<string, unknown>) => ({
    headers: [{ name: 'amt', title: 'amt' }],
    columns: { amt: { alignment: 'right' as const, number } },
  })

  it('groups thousands and fixes decimals for display (en → 1,234,567.89)', () => {
    const w = mountTable(
      [{ amt: 1234567.891 }],
      numberCol({ minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: true }),
    )
    expect(w.find('td .cell-content').text()).toBe('1,234,567.89')
  })

  it('renders a currency column with its unit', () => {
    const w = mountTable(
      [{ amt: 1234.5 }],
      numberCol({ style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 }),
    )
    const text = w.find('td .cell-content').text()
    expect(text).toContain('1,234.50')
    expect(text).toMatch(/\$|USD/)
  })

  it('prefers `number` over the deprecated printf `format`', () => {
    const w = mountTable([{ amt: 1234.5 }], {
      headers: [{ name: 'amt', title: 'amt' }],
      // %.2f alone → "1234.50" (ungrouped); the number descriptor must win.
      columns: {
        amt: { format: '%.2f', number: { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: true } },
      },
    })
    expect(w.find('td .cell-content').text()).toBe('1,234.50')
  })

  it('exports invariant numbers (no grouping, dot decimal) so CSV/TSV stays parseable', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    const w = mountTable(
      [{ amt: 1234567.891 }],
      numberCol({ minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: true }),
    )
    const copyBtn = w.findAll('button').find((b) => b.attributes('aria-label') === 'table.copyTsv')!
    await copyBtn.trigger('click')
    expect(writeText).toHaveBeenCalledTimes(1)
    const tsv = writeText.mock.calls[0]![0] as string
    // Grouped/localized display is "1,234,567.89"; the export must be invariant.
    expect(tsv).toContain('1234567.89')
    expect(tsv).not.toContain('1,234,567.89')
  })
})

describe('TableRenderer — client-side paging (runtime page size)', () => {
  afterEach(() => {
    window.APP_CONFIG = {}
  })

  const rows = (n: number) => Array.from({ length: n }, (_, i) => ({ a: i }))

  it('paginates client-side at the configured page size', () => {
    window.APP_CONFIG = { VITE_TABLE_PAGE_SIZE: '2' }
    const w = mountTable(rows(5))
    // Paginator shows, and only one page of rows (2) is rendered at a time.
    expect(w.find('.p-paginator').exists()).toBe(true)
    expect(w.findAll('tbody tr').length).toBe(2)
  })

  it('shows no paginator when the row count fits one page', () => {
    window.APP_CONFIG = { VITE_TABLE_PAGE_SIZE: '25' }
    const w = mountTable(rows(3))
    expect(w.find('.p-paginator').exists()).toBe(false)
    expect(w.findAll('tbody tr').length).toBe(3)
  })
})
