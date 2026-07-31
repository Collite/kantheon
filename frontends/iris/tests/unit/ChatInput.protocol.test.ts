// review-079 R6 — `/protocol` is single-flight.
//
// The in-flight flag existed but nothing read it, so the most expensive command
// in the surface (three federated sources, 8s timeout each, one DB read per
// turn) could be fired repeatedly. And because a protocol deliberately leaves
// the transcript untouched, the user sees nothing happen until the tab opens —
// which is what invites the second press.
//
// Mount + Pinia/PrimeVue/i18n setup mirrors ChatInput.history.test.ts.
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

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: {} },
})

const SlashCommandPopupStub = { template: '<div class="slash-popup-stub" />' }

vi.mock('@/services/irisStream', () => ({
  irisStream: { refresh: vi.fn() },
}))

// Never resolves: the request stays in flight for the whole test, which is the
// window the guard has to cover.
const requestProtocol = vi.fn(() => new Promise(() => {}))
vi.mock('@/services/protocol', () => ({
  requestProtocol: (...args: unknown[]) => requestProtocol(...(args as [])),
  ProtocolRequestError: class extends Error {
    constructor(
      message: string,
      readonly status: number,
    ) {
      super(message)
    }
  },
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

async function submit(wrapper: ReturnType<typeof mountInput>, text: string) {
  const session = useAgentSession()
  session.prompt.value = text
  await nextTick()
  await wrapper.find('form').trigger('submit')
  await nextTick()
}

describe('ChatInput — /protocol is single-flight', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    requestProtocol.mockClear()
    const s = useAgentSession()
    s.prompt.value = ''
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('a second /protocol while one is in flight issues no second request', async () => {
    const wrapper = mountInput()

    await submit(wrapper, '/protocol')
    expect(requestProtocol).toHaveBeenCalledTimes(1)

    await submit(wrapper, '/protocol')
    await submit(wrapper, '/protocol session')
    expect(requestProtocol).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('the send button shows the work is happening', async () => {
    // Without this the user has no signal at all: the transcript is untouched by
    // design and the tab only appears at the end.
    const wrapper = mountInput()
    await submit(wrapper, '/protocol')

    const button = wrapper.find('button[type="submit"]')
    expect(button.attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })
})
