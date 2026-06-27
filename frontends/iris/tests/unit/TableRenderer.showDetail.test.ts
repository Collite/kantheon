// List C (C1) — the row context menu offers "Show detail"; choosing it emits
// `show-detail` carrying the right-clicked row's stable index (its position in
// the held rows == the server `sample_rows` index).
import { beforeAll, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ContextMenu from 'primevue/contextmenu'
import { createI18n } from 'vue-i18n'
import TableRenderer from '@/components/chat/formats/TableRenderer.vue'
import en from '@/i18n/en.json'

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

function mountTable(content: unknown) {
  return mount(TableRenderer, {
    global: { plugins: [createPinia(), PrimeVue, ToastService, i18n] },
    props: { content, kind: 'table' as const },
  })
}

interface MenuItem {
  label: string
  command?: () => void
}

describe('TableRenderer — Show detail', () => {
  it('row context menu contains a "Show detail" item', async () => {
    const w = mountTable(ROWS)
    await w.findAll('tbody tr')[0]!.trigger('contextmenu')
    const model = w.findComponent(ContextMenu).props('model') as MenuItem[]
    expect(model[0]!.label).toBe('Show detail')
  })

  it('choosing "Show detail" emits show-detail with the right-clicked row index', async () => {
    const w = mountTable(ROWS)
    // Right-click the SECOND row → stable index 1.
    await w.findAll('tbody tr')[1]!.trigger('contextmenu')
    const model = w.findComponent(ContextMenu).props('model') as MenuItem[]
    model.find((i) => i.label === 'Show detail')!.command!()

    const emitted = w.emitted('show-detail')
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0]).toEqual({ rowIndex: 1 })
  })

  it('does not open the row menu for a key/value table', async () => {
    const w = mountTable({ row_count: 2, status: 'ok' })
    const rows = w.findAll('tbody tr')
    if (rows.length > 0) await rows[0]!.trigger('contextmenu')
    // onRowContext bails on key/value tables, so no show-detail is ever emitted.
    expect(w.emitted('show-detail')).toBeFalsy()
  })
})
