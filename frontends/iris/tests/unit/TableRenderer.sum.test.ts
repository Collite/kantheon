// Σ (sum) popover on numeric table columns: the affordance shows only on numeric
// columns (not strings, not the synthetic '#', not key/value tables), and clicking
// it opens a popover with the formatted sum + the visible row count.
import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Tooltip from 'primevue/tooltip'
import { createI18n } from 'vue-i18n'
import TableRenderer from '@/components/chat/formats/TableRenderer.vue'
import { FormatKind } from '@/types/envelope'
import type { TableDetails } from '@/types/envelope'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

// PrimeVue's ContextMenu/Popover bind a matchMedia listener on mount; jsdom has
// no matchMedia, so stub a no-op MediaQueryList.
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

// Track mounts so each test starts with a clean DOM — PrimeVue's Popover
// teleports its content to document.body, which would otherwise leak across tests.
let wrappers: VueWrapper[] = []

function mountTable(content: unknown, details?: unknown, resultTotalRows?: number) {
  const w = mount(TableRenderer, {
    global: {
      plugins: [createPinia(), PrimeVue, ToastService, i18n],
      directives: { tooltip: Tooltip },
    },
    props: {
      content,
      details: details as TableDetails | undefined,
      resultTotalRows,
      kind: FormatKind.TABLE,
    },
  })
  wrappers.push(w)
  return w
}

afterEach(() => {
  wrappers.forEach((w) => w.unmount())
  wrappers = []
})

const ROWS = [
  { name: 'a', qty: 2, price: '1.5' },
  { name: 'b', qty: 3, price: '2.5' },
]

const DETAILS = {
  headers: [
    { name: 'name', title: 'name' },
    { name: 'qty', title: 'qty' },
    { name: 'price', title: 'price' },
  ],
  columns: {
    price: { alignment: 'right', format: '%.2f' },
  },
}

describe('TableRenderer — Σ column sum', () => {
  it('renders a Σ button for numeric columns only (qty, price), not name', () => {
    const w = mountTable(ROWS, DETAILS)
    const labels = w
      .findAll('button.col-sum-btn')
      .map((b) => b.attributes('aria-label'))
    expect(labels).toContain('Show the sum of qty')
    expect(labels).toContain('Show the sum of price')
    expect(labels).not.toContain('Show the sum of name')
    expect(labels.length).toBe(2)
  })

  it('does not render a Σ button for the synthetic # column', () => {
    const w = mountTable([
      { '#': 1, qty: 2 },
      { '#': 2, qty: 3 },
    ])
    const labels = w
      .findAll('button.col-sum-btn')
      .map((b) => b.attributes('aria-label'))
    expect(labels).toEqual(['Show the sum of qty'])
  })

  it('renders no Σ buttons for a key/value table', () => {
    const w = mountTable({ row_count: 2, total: 42 })
    expect(w.findAll('button.col-sum-btn').length).toBe(0)
  })

  it('clicking the qty Σ opens a popover with the sum (5) and the row count (2)', async () => {
    const w = mountTable(ROWS, DETAILS)
    const qtyBtn = w
      .findAll('button.col-sum-btn')
      .find((b) => b.attributes('aria-label') === 'Show the sum of qty')!
    await qtyBtn.trigger('click')
    const popover = document.body.querySelector('.col-sum-popover')
    expect(popover).not.toBeNull()
    const text = popover!.textContent ?? ''
    expect(text).toContain('qty')
    expect(text).toContain('Sum:')
    // Always 2 decimals, even for a column with no format spec (qty).
    expect(text).toContain('5.00')
    expect(text).toContain('2 rows')
  })

  it('always shows 2 decimals regardless of the column format spec (price → 4.00)', async () => {
    const w = mountTable(ROWS, DETAILS)
    const priceBtn = w
      .findAll('button.col-sum-btn')
      .find((b) => b.attributes('aria-label') === 'Show the sum of price')!
    await priceBtn.trigger('click')
    const text = document.body.querySelector('.col-sum-popover')?.textContent ?? ''
    expect(text).toContain('4.00')
  })

  it('groups thousands and fixes 2 decimals for large sums (en → 1,234,567.89)', async () => {
    const w = mountTable([{ amount: 1234567.891 }, { amount: 0.999 }])
    const btn = w.findAll('button.col-sum-btn')[0]!
    await btn.trigger('click')
    const text = document.body.querySelector('.col-sum-popover')?.textContent ?? ''
    expect(text).toContain('1,234,568.89')
  })

  it('reports min, max, avg and empty-cell count alongside the sum', async () => {
    // Values 10/20/30 + one blank: sum 60, min 10, max 30, avg 20, 1 empty cell.
    const w = mountTable([{ v: 10 }, { v: 20 }, { v: null }, { v: 30 }])
    await w.findAll('button.col-sum-btn')[0]!.trigger('click')
    const text = document.body.querySelector('.col-sum-popover')?.textContent ?? ''
    expect(text).toContain('Sum:')
    expect(text).toContain('60.00')
    expect(text).toContain('Min:')
    expect(text).toContain('10.00')
    expect(text).toContain('Max:')
    expect(text).toContain('30.00')
    expect(text).toContain('Avg:')
    expect(text).toContain('20.00')
    expect(text).toContain('Empty cells:')
    // The blank cell is counted as empty; the caption still reports 4 rows shown.
    expect(text).toContain('4 rows')
  })

  it('flags that stats cover only the loaded sample when the result is larger', async () => {
    // FE holds 2 rows but the full result has 100 → partial caption.
    const w = mountTable([{ v: 1 }, { v: 2 }], undefined, 100)
    await w.findAll('button.col-sum-btn')[0]!.trigger('click')
    const text = document.body.querySelector('.col-sum-popover')?.textContent ?? ''
    expect(text).toContain('2 rows')
    expect(text).toContain('100')
    expect(text).toContain('full result')
  })

  it('sums in the column\'s own units when it carries a `number` descriptor', async () => {
    const w = mountTable([{ amount: 1000 }, { amount: 234.5 }], {
      headers: [{ name: 'amount', title: 'amount' }],
      columns: {
        amount: { alignment: 'right', number: { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 } },
      },
    })
    const btn = w.findAll('button.col-sum-btn')[0]!
    await btn.trigger('click')
    const text = document.body.querySelector('.col-sum-popover')?.textContent ?? ''
    expect(text).toContain('1,234.50')
    expect(text).toMatch(/\$|USD/)
  })
})
