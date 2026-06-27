// Stage 07-B B-4 — DrilldownChips prefill behaviour.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import DrilldownChips from '@/components/chat/DrilldownChips.vue'
import type { Drilldown } from '@/types/envelope'

function mountChips(drilldowns: Drilldown[], row: Record<string, unknown>) {
  return mount(DrilldownChips, {
    global: { plugins: [createPinia(), PrimeVue] },
    props: { drilldowns, row },
  })
}

const detailDrilldown: Drilldown = {
  id: 'd1',
  display: 'Detail dokladu',
  targetPatternId: 'ucetni_doklad_detail',
  argMapping: { id_ucetniho_zapisu: 'IDUCETZAP' },
  scope: 'row',
  source: 'explicit_ttr',
}

describe('DrilldownChips', () => {
  it('renders a chip per row-scope drilldown', () => {
    const w = mountChips([detailDrilldown], { IDUCETZAP: 12345 })
    expect(w.findAll('.drilldown-chip')).toHaveLength(1)
    expect(w.find('.drilldown-chip').text()).toContain('Detail dokladu')
  })

  it('filters out point-scope drilldowns', () => {
    const point: Drilldown = { ...detailDrilldown, id: 'd2', scope: 'point' }
    const w = mountChips([detailDrilldown, point], { IDUCETZAP: 1 })
    expect(w.findAll('.drilldown-chip')).toHaveLength(1)
  })

  it('emits a templated user_text on click (no network call)', async () => {
    const w = mountChips([detailDrilldown], { IDUCETZAP: 99999 })
    await w.find('.drilldown-chip').trigger('click')

    const emitted = w.emitted('prefill')
    expect(emitted).toBeTruthy()
    const [text, drill] = emitted![0]!
    expect(String(text)).toContain('Detail dokladu')
    expect(String(text)).toContain('99999')
    expect((drill as Drilldown).targetPatternId).toBe('ucetni_doklad_detail')
  })

  it('looks up argMapping values case-insensitively', async () => {
    // Row uses lowercase keys; argMapping references the uppercase column name.
    const w = mountChips([detailDrilldown], { iducetzap: 42 })
    await w.find('.drilldown-chip').trigger('click')
    const [text] = w.emitted('prefill')![0]!
    expect(String(text)).toContain('42')
  })

  it('preserves quoted-literal source expressions verbatim', async () => {
    const drill: Drilldown = {
      ...detailDrilldown,
      argMapping: { mode: "'detail'" },
    }
    const w = mountChips([drill], {})
    await w.find('.drilldown-chip').trigger('click')
    const [text] = w.emitted('prefill')![0]!
    expect(String(text)).toContain('mode=detail')
  })
})
