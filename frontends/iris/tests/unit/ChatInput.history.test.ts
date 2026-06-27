// Phase 2 — ChatInput ↔ usePromptHistory integration.
// Mirrors ClarificationCard.test.ts for the mount + Pinia/PrimeVue/i18n setup.
// We drive the real useAgentSession singleton (no network at mount) and seed
// history through localStorage so the composable loads it on construction.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import { createI18n } from 'vue-i18n'
import ChatInput from '@/components/chat/ChatInput.vue'
import { useAgentSession } from '@/composables/useAgentSession'

const HISTORY_KEY = 'golem.promptHistory.v1'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: {} },
})

// Stub the popup so we don't need to provide every `slash.*` description key;
// the keydown popup branch lives in ChatInput itself and is unaffected.
const SlashCommandPopupStub = { template: '<div class="slash-popup-stub" />' }

vi.mock('@/services/irisStream', () => ({
  irisStream: { refresh: vi.fn() },
}))

function mountInput() {
  return mount(ChatInput, {
    global: {
      plugins: [createPinia(), PrimeVue, ToastService, ConfirmationService, i18n],
      stubs: { SlashCommandPopup: SlashCommandPopupStub },
    },
    props: { placeholder: 'ask', sendLabel: 'Send' },
  })
}

function pressKey(wrapper: ReturnType<typeof mountInput>, key: string) {
  const input = wrapper.find('#chat-input').element as HTMLInputElement
  const ev = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
  input.dispatchEvent(ev)
  return ev
}

describe('ChatInput — prompt history recall', () => {
  beforeEach(() => {
    localStorage.clear()
    // Seed two prior questions, newest last.
    localStorage.setItem(HISTORY_KEY, JSON.stringify(['first question', 'second question']))
    setActivePinia(createPinia())
    // Reset the singleton session state between tests.
    const s = useAgentSession()
    s.prompt.value = ''
    s.dryRunNext.value = false
    s.desiredFormat.value = null
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('ArrowUp on an empty input recalls the last question', async () => {
    const w = mountInput()
    const session = useAgentSession()

    const ev = pressKey(w, 'ArrowUp')
    await nextTick()

    expect(session.prompt.value).toBe('second question')
    expect(ev.defaultPrevented).toBe(true)
  })

  it('repeated ArrowUp walks further back', async () => {
    const w = mountInput()
    const session = useAgentSession()

    pressKey(w, 'ArrowUp')
    await nextTick()
    pressKey(w, 'ArrowUp')
    await nextTick()

    expect(session.prompt.value).toBe('first question')
  })

  it('ArrowUp with text in the box leaves it unchanged (no preventDefault)', async () => {
    const w = mountInput()
    const session = useAgentSession()
    session.prompt.value = 'half typed'
    await nextTick()

    const ev = pressKey(w, 'ArrowUp')
    await nextTick()

    expect(session.prompt.value).toBe('half typed')
    expect(ev.defaultPrevented).toBe(false)
  })

  it('ArrowDown after recalling restores the (empty) draft and exits', async () => {
    const w = mountInput()
    const session = useAgentSession()

    pressKey(w, 'ArrowUp') // → 'second question'
    await nextTick()
    const ev = pressKey(w, 'ArrowDown') // past newest → restore '' draft
    await nextTick()

    expect(session.prompt.value).toBe('')
    expect(ev.defaultPrevented).toBe(true)
  })

  it('does not touch history while the slash popup is open', async () => {
    const w = mountInput()
    const session = useAgentSession()
    session.prompt.value = '/'
    await nextTick()

    const ev = pressKey(w, 'ArrowUp')
    await nextTick()

    // Popup branch handled the key — input stays '/', not a recalled question.
    expect(session.prompt.value).toBe('/')
    expect(ev.defaultPrevented).toBe(true)
  })

  it('typing a printable key exits navigation, re-arming the empty rule', async () => {
    const w = mountInput()
    const session = useAgentSession()

    pressKey(w, 'ArrowUp') // navigating → 'second question'
    await nextTick()
    expect(session.prompt.value).toBe('second question')

    // Simulate the user editing: the model updates, then a printable keydown.
    session.prompt.value = 'second question typed'
    await nextTick()
    pressKey(w, 'x')
    await nextTick()

    // Navigation reset: ArrowDown is now a no-op (not navigating).
    const ev = pressKey(w, 'ArrowDown')
    await nextTick()
    expect(ev.defaultPrevented).toBe(false)
    expect(session.prompt.value).toBe('second question typed')
  })
})
