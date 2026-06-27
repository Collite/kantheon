// Iris Phase 3 Stage 3.2 T5 — ChipStrip discriminates the envelope/v1 Chip
// oneof (prompt | routing | investigate) and routes each click to the right
// typed event; RoutingPickChip carries the agent label + why.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { createI18n } from 'vue-i18n'
import ChipStrip from '@/components/chat/ChipStrip.vue'
import RoutingPickChip from '@/components/chat/RoutingPickChip.vue'
import InvestigateChip from '@/components/chat/InvestigateChip.vue'
import type { Chip, RoutingPickChip as RoutingPickChipT } from '@/types/envelope'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: { chat: { chipStrip: { ariaLabel: 'Suggested', chipAriaLabel: 'Query: {label}' } } },
  },
})

const mountOpts = { global: { plugins: [createPinia(), PrimeVue, i18n] } }

const promptChip: Chip = { prompt: { display: 'Sales by region', prompt: 'Sales by region', source: 'static' } } as Chip
const routingChip: Chip = {
  routing: { agentId: { value: 'golem-sales' }, label: 'Sales agent', why: 'sales question' },
} as Chip
const investigateChip: Chip = {
  investigate: { proposedQuestion: 'Why did margin drop?', label: 'Investigate this' },
} as Chip

describe('ChipStrip', () => {
  it('renders nothing when chips is empty', () => {
    const w = mount(ChipStrip, { ...mountOpts, props: { chips: [] } })
    expect(w.find('.chip-strip').exists()).toBe(false)
  })

  it('renders one chip per oneof arm with the right component', () => {
    const w = mount(ChipStrip, {
      ...mountOpts,
      props: { chips: [promptChip, routingChip, investigateChip] },
    })
    expect(w.findComponent(RoutingPickChip).exists()).toBe(true)
    expect(w.findComponent(InvestigateChip).exists()).toBe(true)
    expect(w.find('.prompt-chip').exists()).toBe(true)
  })

  it('emits prompt with the PromptChip on a prompt-chip click', async () => {
    const w = mount(ChipStrip, { ...mountOpts, props: { chips: [promptChip] } })
    await w.find('.prompt-chip').trigger('click')
    const emitted = w.emitted('prompt')
    expect(emitted).toBeTruthy()
    expect((emitted![0]![0] as { prompt: string }).prompt).toBe('Sales by region')
  })

  it('emits pick(agentId, label) when a RoutingPickChip is clicked', async () => {
    const w = mount(ChipStrip, { ...mountOpts, props: { chips: [routingChip] } })
    await w.findComponent(RoutingPickChip).find('.routing-pick-chip').trigger('click')
    expect(w.emitted('pick')).toEqual([['golem-sales', 'Sales agent']])
  })

  it('emits investigate with the chip when an InvestigateChip is clicked', async () => {
    const w = mount(ChipStrip, { ...mountOpts, props: { chips: [investigateChip] } })
    await w.findComponent(InvestigateChip).find('.investigate-chip').trigger('click')
    const emitted = w.emitted('investigate')
    expect(emitted).toBeTruthy()
    expect((emitted![0]![0] as { proposedQuestion: string }).proposedQuestion).toBe('Why did margin drop?')
  })
})

describe('RoutingPickChip', () => {
  const chip: RoutingPickChipT = { agentId: { value: 'pythia' }, label: 'Pythia', why: 'analytical' }

  it('shows the agent label and why subtext', () => {
    const w = mount(RoutingPickChip, { ...mountOpts, props: { chip } })
    expect(w.find('.routing-pick-label').text()).toBe('Pythia')
    expect(w.find('.routing-pick-why').text()).toBe('analytical')
  })

  it('does not emit pick when the chip carries no agent id', async () => {
    const w = mount(RoutingPickChip, {
      ...mountOpts,
      props: { chip: { label: 'X', why: 'y' } as RoutingPickChipT },
    })
    await w.find('.routing-pick-chip').trigger('click')
    expect(w.emitted('pick')).toBeFalsy()
  })
})
