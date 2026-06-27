// List D (D4) — when a row-detail selection is armed, ChatInput shows a chip so
// the user sees that "this" is bound; its × clears the selection.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import { createI18n } from 'vue-i18n'
import ChatInput from '@/components/chat/ChatInput.vue'
import { useAgentSession } from '@/composables/useAgentSession'
import en from '@/i18n/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en } })

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

describe('ChatInput — row-detail selection chip', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAgentSession().clearSelection()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    useAgentSession().clearSelection()
  })

  it('renders no chip when nothing is armed', () => {
    const w = mountInput()
    expect(w.find('.selection-tag').exists()).toBe(false)
  })

  it('renders a chip when a selection is armed', async () => {
    const session = useAgentSession()
    const w = mountInput()
    session.armSelection({ bubble_id: 'b1', row_indices: [0] })
    await w.vm.$nextTick()
    const chip = w.find('.selection-tag')
    expect(chip.exists()).toBe(true)
    expect(chip.text()).toContain('Row selected')
  })

  it('clicking the × clears the armed selection', async () => {
    const session = useAgentSession()
    const w = mountInput()
    session.armSelection({ bubble_id: 'b1', row_indices: [0] })
    await w.vm.$nextTick()

    await w.find('.selection-tag .hint-clear').trigger('click')

    expect(session.armedSelection.value).toBeNull()
    expect(w.find('.selection-tag').exists()).toBe(false)
  })
})
