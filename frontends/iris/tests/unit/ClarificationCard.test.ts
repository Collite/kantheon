// ClarificationCard — Phase 2 Stage 2.2 re-point: resume rides the BFF SSE
// surface (POST /v1/chat/resume), so the card drives `irisStream.resumeClarification`
// with handlers and emits the terminal envelope arm.
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { createI18n } from 'vue-i18n'
import ClarificationCard from '@/components/chat/ClarificationCard.vue'
import { FormatEnvelope, FormatKind } from '@/types/envelope'
import type { PendingClarification } from '@/types/envelope'
import type { StreamHandlers } from '@/services/irisStream'

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
      },
      errors: { agentFailed: 'Agent failed' },
    },
  },
})

vi.mock('@/services/irisStream', () => ({
  irisStream: {
    resumeClarification: vi.fn(),
  },
}))

import { irisStream } from '@/services/irisStream'

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(irisStream.resumeClarification).mockReset()
  vi.mocked(irisStream.resumeClarification).mockResolvedValue(undefined)
})

afterEach(() => {
  vi.restoreAllMocks()
})

function mountCard(pending: PendingClarification) {
  return mount(ClarificationCard, {
    global: { plugins: [createPinia(), PrimeVue, ToastService, i18n] },
    props: { threadId: 't-1', pendingClarification: pending },
  })
}

const pending: PendingClarification = {
  kind: 'intent_choice',
  resumeToken: 'tok-1',
  options: [
    { id: 'opt_1', display: 'Option A', description: 'first' },
    { id: 'opt_2', display: 'Option B' },
  ],
  contextText: 'ctx',
}

describe('ClarificationCard', () => {
  it('renders one button per option', () => {
    const w = mountCard(pending)
    const labels = w.findAll('.option-btn').map(b => b.text())
    expect(labels).toContain('Option A')
    expect(labels).toContain('Option B')
  })

  it('resumes with the picked option id (camelCase BFF shape)', async () => {
    const w = mountCard(pending)
    await w.findAll('.option-btn')[0]!.trigger('click')

    expect(irisStream.resumeClarification).toHaveBeenCalledWith(
      { sessionId: 't-1', resumeToken: 'tok-1', selectedOptionId: 'opt_1' },
      expect.anything(),
    )
  })

  it('Other expands a free-text input that submits via freeTextAnswer', async () => {
    const w = mountCard(pending)
    const allButtons = w.findAll('button')
    const otherBtn = allButtons.find(b => b.text().includes('Other'))!
    await otherBtn.trigger('click')

    const input = w.find('input')
    expect(input.exists()).toBe(true)
    await input.setValue('My custom answer')

    const submitBtn = w.findAll('button').find(b => b.text().includes('Submit'))!
    await submitBtn.trigger('click')

    expect(irisStream.resumeClarification).toHaveBeenCalledWith(
      { sessionId: 't-1', resumeToken: 'tok-1', freeTextAnswer: 'My custom answer' },
      expect.anything(),
    )
  })

  it('emits `resumed` with the envelope arm from the resume stream', async () => {
    const envelope = FormatEnvelope.create({
      bubbleId: 'b',
      turnId: 't',
      threadId: 't-1',
      format: { kind: FormatKind.TABLE },
      createdAt: 'now',
      agentVersion: 'g',
    })
    vi.mocked(irisStream.resumeClarification).mockImplementation(
      async (_req, handlers: StreamHandlers) => {
        handlers.onEnvelope?.(envelope)
      },
    )

    const w = mountCard(pending)
    await w.findAll('.option-btn')[1]!.trigger('click')
    await Promise.resolve()
    await Promise.resolve()
    const emitted = w.emitted('resumed')
    expect(emitted).toBeTruthy()
    expect((emitted![0]![0] as { bubbleId: string }).bubbleId).toBe('b')
  })
})
