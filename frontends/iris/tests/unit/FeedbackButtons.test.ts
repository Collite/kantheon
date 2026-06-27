// Iris Phase 4 Stage 4.3 — FeedbackButtons (PD-3 👍/👎 + reason picker).
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Tooltip from 'primevue/tooltip'
import { createI18n } from 'vue-i18n'

const submit = vi.fn().mockResolvedValue({ turnId: 't1', verdict: 'up' })
vi.mock('@/services/feedback', () => ({ feedbackApi: { submit: (...a: unknown[]) => submit(...a) } }))

import FeedbackButtons from '@/components/chat/FeedbackButtons.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      chat: {
        feedback: {
          up: 'Helpful',
          down: 'Not helpful',
          thanks: 'Thanks',
          failed: 'Failed',
          reasonPrompt: 'What was off?',
          reasons: { wrong_data: 'Wrong data', wrong_agent: 'Wrong agent', wrong_format: 'Wrong format', too_slow: 'Too slow', other: 'Other' },
        },
      },
    },
  },
})

function mountButtons() {
  return mount(FeedbackButtons, {
    global: { plugins: [createPinia(), PrimeVue, ToastService, i18n], directives: { tooltip: Tooltip } },
    props: { turnId: 't1' },
  })
}

beforeEach(() => submit.mockClear())

describe('FeedbackButtons', () => {
  it('submits an up verdict and shows no reason picker', async () => {
    const w = mountButtons()
    await w.find('button[aria-label="Helpful"]').trigger('click')
    expect(submit).toHaveBeenCalledWith('t1', 'up', undefined)
    expect(w.find('.fb-reasons').exists()).toBe(false)
  })

  it('submits a down verdict and reveals the reason picker', async () => {
    const w = mountButtons()
    await w.find('button[aria-label="Not helpful"]').trigger('click')
    expect(submit).toHaveBeenCalledWith('t1', 'down', undefined)
    expect(w.find('.fb-reasons').exists()).toBe(true)
    expect(w.findAll('.fb-reason').length).toBe(5)
  })

  it('resubmits down with a reason on a reason chip click', async () => {
    const w = mountButtons()
    await w.find('button[aria-label="Not helpful"]').trigger('click')
    submit.mockClear()
    await w.findAll('.fb-reason')[0]!.trigger('click')
    expect(submit).toHaveBeenCalledWith('t1', 'down', 'wrong_data')
    expect(w.find('.fb-reasons').exists()).toBe(false)
  })
})
