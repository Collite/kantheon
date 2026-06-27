// Stage clarif-resume 01 (T1) — ChatBubble mounts ClarificationCard whenever the
// assistant envelope carries a `pendingClarification`. This is the single
// mount point for the clarification UI (D2): options reach the user via the
// card, never via the chip strip.
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Tooltip from 'primevue/tooltip'
import { createI18n } from 'vue-i18n'
import { FormatEnvelope, FormatKind } from '@/types/envelope'

// Keep the renderer trivial — we only care about the clarification card mount,
// not markdown-it / mermaid (which choke in jsdom).
vi.mock('@/catalog/formatCatalog', () => ({
  resolveRenderer: () => defineComponent({ render: () => h('div', { class: 'stub-renderer' }) }),
}))

vi.mock('@/services/irisStream', () => ({
  irisStream: {
    resumeClarification: vi.fn(),
    turn: vi.fn(),
  },
}))

import ChatBubble from '@/components/chat/ChatBubble.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: {
    en: {
      chat: {
        clarification: {
          ariaLabel: 'Clarification',
          other: 'Other',
          otherPlaceholder: 'Custom',
          submit: 'Submit',
        },
        copy: 'Copy',
        openInTab: 'Open in Tab',
        openInTabNoEnvelope: 'No envelope',
        openInTabPlaintext: 'Plaintext',
        openInTabComing: 'Coming',
      },
      errors: { agentFailed: 'Agent failed' },
    },
  },
})

function makeEnvelope(overrides: Partial<FormatEnvelope> = {}): FormatEnvelope {
  return FormatEnvelope.create({
    bubbleId: 'b-1',
    turnId: 't-1',
    threadId: 'th-1',
    text: 'Upřesněte prosím výběr.',
    format: { kind: FormatKind.MARKDOWN },
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
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('ChatBubble clarification mount', () => {
  it('mounts ClarificationCard when the assistant envelope has a pendingClarification', () => {
    const envelope = makeEnvelope({
      pendingClarification: {
        kind: 'entity_choice',
        resumeToken: 'tok',
        options: [{ id: 'ent_1', display: 'EX KANCELAR' }],
      },
    })
    const w = mountBubble(envelope)
    const card = w.find('[role="region"]')
    expect(card.exists()).toBe(true)
    expect(card.findAll('.option-btn').map(b => b.text())).toContain('EX KANCELAR')
  })

  it('does NOT mount ClarificationCard when pendingClarification is absent', () => {
    const w = mountBubble(makeEnvelope())
    expect(w.find('[role="region"]').exists()).toBe(false)
  })

  it('does NOT mount ClarificationCard for an empty-options, non-param_fill payload', () => {
    // A normal table output must not paste the bare "Other…" box. Only a
    // genuine clarification (options present, or a param_fill free-text ask)
    // mounts the card.
    const envelope = makeEnvelope({
      pendingClarification: {
        kind: 'intent_choice',
        resumeToken: 'tok',
        options: [],
      },
    })
    const w = mountBubble(envelope)
    expect(w.find('[role="region"]').exists()).toBe(false)
  })

  it('mounts ClarificationCard for a param_fill even with no display options', () => {
    const envelope = makeEnvelope({
      pendingClarification: {
        kind: 'param_fill',
        resumeToken: 'tok',
        options: [{ id: 'datum_od', display: 'Datum od' }],
        contextText: 'Zadejte datum od',
      },
    })
    const w = mountBubble(envelope)
    expect(w.find('[role="region"]').exists()).toBe(true)
  })

  it('does NOT mount ClarificationCard for a user bubble', () => {
    const envelope = makeEnvelope({
      pendingClarification: {
        kind: 'entity_choice',
        resumeToken: 'tok',
        options: [{ id: 'ent_1', display: 'EX KANCELAR' }],
      },
    })
    const w = mountBubble(envelope, 'user')
    expect(w.find('[role="region"]').exists()).toBe(false)
  })
})
