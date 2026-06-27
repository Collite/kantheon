// Iris Phase 3 Stage 3.2 T5 — TableRenderer drives the data-shaping typed
// actions. DataTable's sort/filter/page events map to {column, direction} /
// {column, operator, value} / {page, pageSize}; a row drilldown menu item maps
// to a select_row drilldown by stable row index.
import { beforeAll, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ContextMenu from 'primevue/contextmenu'
import DataTable from 'primevue/datatable'
import { createI18n } from 'vue-i18n'
import TableRenderer from '@/components/chat/formats/TableRenderer.vue'
import en from '@/i18n/en.json'
import type { Drilldown } from '@/types/envelope'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

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

const ROWS = [
  { name: 'a', qty: 2 },
  { name: 'b', qty: 3 },
]

function mountTable(props: Record<string, unknown> = {}) {
  return mount(TableRenderer, {
    global: { plugins: [createPinia(), PrimeVue, ToastService, i18n] },
    props: { content: ROWS, kind: 'table' as const, ...props },
  })
}

interface MenuItem {
  label: string
  command?: () => void
}

describe('TableRenderer — typed actions', () => {
  it('emits sort {column, direction} from a DataTable sort event (desc)', async () => {
    const w = mountTable()
    w.findComponent(DataTable).vm.$emit('sort', { sortField: 'qty', sortOrder: -1 })
    expect(w.emitted('sort')![0]![0]).toEqual({ column: 'qty', direction: 'desc' })
  })

  it('maps sortOrder 1 to asc', async () => {
    const w = mountTable()
    w.findComponent(DataTable).vm.$emit('sort', { sortField: 'name', sortOrder: 1 })
    expect(w.emitted('sort')![0]![0]).toEqual({ column: 'name', direction: 'asc' })
  })

  it('emits filter {column, operator, value} from the first active column filter', async () => {
    const w = mountTable()
    w.findComponent(DataTable).vm.$emit('filter', {
      filters: { name: { value: 'ab', matchMode: 'contains' }, qty: { value: null } },
    })
    expect(w.emitted('filter')![0]![0]).toEqual({ column: 'name', operator: 'contains', value: 'ab' })
  })

  it('maps the equals match mode to the eq operator', async () => {
    const w = mountTable()
    w.findComponent(DataTable).vm.$emit('filter', {
      filters: { qty: { value: 3, matchMode: 'equals' } },
    })
    expect(w.emitted('filter')![0]![0]).toEqual({ column: 'qty', operator: 'eq', value: 3 })
  })

  it('emits paginate with a 1-based page', async () => {
    const w = mountTable()
    w.findComponent(DataTable).vm.$emit('page', { page: 2, rows: 25 })
    expect(w.emitted('paginate')![0]![0]).toEqual({ page: 3, pageSize: 25 })
  })

  it('emits drilldown with the right-clicked row index from a drilldown menu item', async () => {
    const drilldowns: Drilldown[] = [
      {
        id: 'd1',
        display: 'Detail',
        targetPatternId: 'detail_p',
        argMapping: { id: 'name' },
        scope: 'row',
        source: 'explicit_ttr',
      },
    ]
    const w = mountTable({ drilldowns })
    await w.findAll('tbody tr')[1]!.trigger('contextmenu')
    const model = w.findComponent(ContextMenu).props('model') as MenuItem[]
    model.find((i) => i.label === 'Detail')!.command!()
    expect(w.emitted('drilldown')![0]![0]).toEqual({ rowIndex: 1 })
  })
})
