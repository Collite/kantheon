// Iris Phase 3 Stage 3.2 T6 (FE) — agent badge + re-ask picker.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { createI18n } from 'vue-i18n'
import AgentBadge, { type ReaskCandidate } from '@/components/chat/AgentBadge.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      chat: {
        reask: { badge: 'Answered by {agent}', menuTitle: 'Re-ask with a different agent' },
      },
    },
  },
})

function mountBadge(label: string, candidates: ReaskCandidate[]) {
  return mount(AgentBadge, {
    global: { plugins: [createPinia(), PrimeVue, i18n] },
    props: { label, candidates },
    attachTo: document.body,
  })
}

describe('AgentBadge', () => {
  it('shows the answering agent label', () => {
    const w = mountBadge('golem-erp', [])
    expect(w.find('.agent-badge-text').text()).toBe('Answered by golem-erp')
  })

  it('is not clickable when there are no candidates', () => {
    const w = mountBadge('golem-erp', [])
    expect(w.find('.agent-badge').classes()).not.toContain('clickable')
    expect(w.find('.agent-badge-caret').exists()).toBe(false)
  })

  it('opens a picker and emits reask with the chosen agent id', async () => {
    const candidates: ReaskCandidate[] = [
      { agentId: 'pythia', label: 'Pythia', why: 'analytical' },
      { agentId: 'golem-sales', label: 'Sales' },
    ]
    const w = mountBadge('golem-erp', candidates)
    expect(w.find('.agent-badge').classes()).toContain('clickable')
    await w.find('.agent-badge').trigger('click')

    const items = document.querySelectorAll('.reask-item')
    expect(items.length).toBe(2)
    // Pre-sorted by alternates: Pythia (with its why) first.
    expect(items[0]!.querySelector('.reask-item-label')!.textContent).toBe('Pythia')
    expect(items[0]!.querySelector('.reask-item-why')!.textContent).toBe('analytical')
    ;(items[0] as HTMLElement).click()
    await w.vm.$nextTick()
    expect(w.emitted('reask')).toEqual([['pythia']])
    w.unmount()
  })
})
