// Iris Phase 3 Stage 3.2 T7 (FE) — the always-on "Investigate this" affordance
// on table/chart answers, and the InvestigateChip render arm. Clicking either
// escalates the turn via the `investigate` typed action (PD-1).
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Tooltip from 'primevue/tooltip'
import { createI18n } from 'vue-i18n'
import { FormatEnvelope, FormatKind } from '@/types/envelope'

vi.mock('@/catalog/formatCatalog', () => ({
  resolveRenderer: () => defineComponent({ render: () => h('div', { class: 'stub-renderer' }) }),
}))

const investigate = vi.fn().mockResolvedValue(undefined)
const reaskAgent = vi.fn().mockResolvedValue(undefined)
vi.mock('@/services/typedAction', () => ({
  typedAction: {
    investigate: (...a: unknown[]) => investigate(...a),
    reaskAgent: (...a: unknown[]) => reaskAgent(...a),
    chipInvocation: vi.fn().mockResolvedValue(undefined),
  },
}))

vi.mock('@/services/irisStream', () => ({
  irisStream: {
    listSessions: vi.fn().mockResolvedValue([]),
    streamTurn: vi.fn().mockResolvedValue(undefined),
    turn: vi.fn(),
    resumeClarification: vi.fn(),
  },
}))

const createPin = vi.fn().mockResolvedValue({ artifactId: 'a1' })
vi.mock('@/services/artifacts', () => ({
  artifactsApi: { createPin: (...a: unknown[]) => createPin(...a) },
}))

import ChatBubble from '@/components/chat/ChatBubble.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: {
    en: {
      chat: {
        copy: 'Copy',
        openInTab: 'Open in Tab',
        openInTabNoEnvelope: 'No envelope',
        openInTabPlaintext: 'Plaintext',
        openInTabComing: 'Coming',
        investigate: { label: 'Investigate', tooltip: 'Escalate' },
        pin: { label: 'Pin', tooltip: 'Pin', pinned: 'Pinned', failed: 'Pin failed' },
        chipStrip: { ariaLabel: 'Suggested', chipAriaLabel: 'Query: {label}' },
        reask: { badge: 'Answered by {agent}', menuTitle: 'Re-ask' },
      },
      errors: { agentFailed: 'Agent failed' },
    },
  },
})

function makeEnvelope(overrides: Partial<FormatEnvelope> = {}): FormatEnvelope {
  return FormatEnvelope.create({
    bubbleId: 'b-1',
    turnId: 't-7',
    threadId: 'th-1',
    text: 'Result',
    format: { kind: FormatKind.TABLE },
    createdAt: 'now',
    agentVersion: 'g',
    ...overrides,
  })
}

function mountBubble(envelope?: FormatEnvelope, role: 'user' | 'assistant' = 'assistant') {
  return mount(ChatBubble, {
    global: {
      plugins: [createPinia(), PrimeVue, ToastService, i18n],
      directives: { tooltip: Tooltip },
    },
    props: { role, content: envelope?.text ?? '', envelope },
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  investigate.mockClear()
  reaskAgent.mockClear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('ChatBubble — Investigate affordance', () => {
  it('shows the Investigate button on a table answer', () => {
    const w = mountBubble(makeEnvelope())
    expect(w.find('.investigate-btn').exists()).toBe(true)
  })

  it('does NOT show it on a plaintext answer', () => {
    const w = mountBubble(makeEnvelope({ format: { kind: FormatKind.PLAINTEXT } }))
    expect(w.find('.investigate-btn').exists()).toBe(false)
  })

  it('pins the table bubble via the artifacts client', async () => {
    createPin.mockClear()
    const w = mountBubble(makeEnvelope({ text: 'Revenue table' }))
    await w.find('.pin-btn').trigger('click')
    expect(createPin).toHaveBeenCalledOnce()
    expect(createPin.mock.calls[0]![0]).toMatchObject({ turnId: 't-7', bubbleId: 'b-1', name: 'Revenue table' })
  })

  it('does NOT show it on a user bubble', () => {
    const w = mountBubble(makeEnvelope(), 'user')
    expect(w.find('.investigate-btn').exists()).toBe(false)
  })

  it('dispatches the investigate typed action with the turn id on click', async () => {
    const w = mountBubble(makeEnvelope())
    await w.find('.investigate-btn').trigger('click')
    expect(investigate).toHaveBeenCalledOnce()
    expect(investigate.mock.calls[0]![0]).toMatchObject({ turnId: 't-7' })
  })

  it('shows the agent badge on an answered bubble and re-asks via the picker', async () => {
    const w = mountBubble(
      makeEnvelope({
        agentId: 'golem-erp',
        chips: [{ routing: { agentId: { value: 'pythia' }, label: 'Pythia', why: 'analytical' } }],
      }),
    )
    const badge = w.find('.agent-badge')
    expect(badge.exists()).toBe(true)
    expect(w.find('.agent-badge-text').text()).toBe('Answered by golem-erp')
    await badge.trigger('click')
    const item = document.querySelector('.reask-item') as HTMLElement | null
    expect(item).not.toBeNull()
    item!.click()
    await w.vm.$nextTick()
    expect(reaskAgent).toHaveBeenCalledOnce()
    expect(reaskAgent.mock.calls[0]![0]).toMatchObject({ turnId: 't-7', targetAgentId: 'pythia' })
  })

  it('renders an InvestigateChip from the envelope chips and escalates on click', async () => {
    const w = mountBubble(
      makeEnvelope({
        chips: [{ investigate: { proposedQuestion: 'Why down?', label: 'Investigate this' } }],
      }),
    )
    const chip = w.find('.investigate-chip')
    expect(chip.exists()).toBe(true)
    await chip.trigger('click')
    expect(investigate).toHaveBeenCalledOnce()
    expect(investigate.mock.calls[0]![0]).toMatchObject({ turnId: 't-7', proposedQuestion: 'Why down?' })
  })
})
