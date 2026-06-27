// Iris Phase 2 Stage 2.3 T1 — the left session rail: lists the caller's
// sessions, highlights the active one, switches on click, "+ New" mints a
// session, and exposes the post-reset Undo affordance.
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ref } from 'vue'
import PrimeVue from 'primevue/config'
import { createI18n } from 'vue-i18n'
import SessionRail from '@/components/layout/SessionRail.vue'
import type { SessionSummaryDto } from '@/types/agent-responses'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: {
    en: {
      sessions: {
        title: 'Sessions',
        new: 'New session',
        newAria: 'Start a new session',
        empty: 'No sessions yet',
        untitled: 'New session',
        turnCount: '{count} turns',
        undoReset: 'Undo reset',
        undoResetAria: 'Restore the conversation you just reset',
      },
    },
  },
})

// Reactive composable doubles shared with the mock.
const sessions = ref<SessionSummaryDto[]>([])
const sessionId = ref('s-1')
const lastResetUndoable = ref(false)
const switchSession = vi.fn()
const startNewSession = vi.fn()
const loadSessions = vi.fn()
const undoLastReset = vi.fn()

vi.mock('@/composables/useAgentSession', () => ({
  useAgentSession: () => ({
    sessions,
    sessionId,
    lastResetUndoable,
    switchSession,
    startNewSession,
    loadSessions,
    undoLastReset,
  }),
}))

function mountRail() {
  return mount(SessionRail, { global: { plugins: [PrimeVue, i18n] } })
}

beforeEach(() => {
  setActivePinia(createPinia())
  sessions.value = []
  sessionId.value = 's-1'
  lastResetUndoable.value = false
  switchSession.mockReset()
  startNewSession.mockReset()
  loadSessions.mockReset()
  undoLastReset.mockReset()
})

describe('SessionRail', () => {
  it('loads sessions on mount and shows the empty state when there are none', () => {
    const wrapper = mountRail()
    expect(loadSessions).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('No sessions yet')
  })

  it('renders a row per session and highlights the active one', () => {
    sessions.value = [
      { sessionId: 's-1', title: 'kolik mám tržeb?', turnCount: 3, updatedAt: 'now' },
      { sessionId: 's-2', title: 'New session', turnCount: 0, updatedAt: 'before' },
    ]
    const wrapper = mountRail()
    const rows = wrapper.findAll('.session-row')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('kolik mám tržeb?')
    expect(rows[0]!.classes()).toContain('session-row--active')
    expect(rows[1]!.classes()).not.toContain('session-row--active')
  })

  it('switches to a session on click (but not the already-active one)', async () => {
    sessions.value = [
      { sessionId: 's-1', title: 'A', turnCount: 1, updatedAt: 'now' },
      { sessionId: 's-2', title: 'B', turnCount: 1, updatedAt: 'before' },
    ]
    const wrapper = mountRail()
    const rows = wrapper.findAll('.session-row')
    await rows[1]!.trigger('click')
    expect(switchSession).toHaveBeenCalledWith('s-2')
    // Clicking the active row is a no-op.
    switchSession.mockReset()
    await rows[0]!.trigger('click')
    expect(switchSession).not.toHaveBeenCalled()
  })

  it('"+ New" mints a fresh session', async () => {
    const wrapper = mountRail()
    await wrapper.find('.new-session-btn').trigger('click')
    expect(startNewSession).toHaveBeenCalledOnce()
  })

  it('shows the Undo affordance only after a reset, and calls undoLastReset', async () => {
    const wrapper = mountRail()
    expect(wrapper.find('.undo-reset-btn').exists()).toBe(false)
    lastResetUndoable.value = true
    await wrapper.vm.$nextTick()
    const undo = wrapper.find('.undo-reset-btn')
    expect(undo.exists()).toBe(true)
    await undo.trigger('click')
    expect(undoLastReset).toHaveBeenCalledOnce()
  })
})
