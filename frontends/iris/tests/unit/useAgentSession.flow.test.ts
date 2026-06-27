// Iris Phase 2 Stage 2.3 T6 — the multi-session daily-driver flow end-to-end at
// the composable layer: switch (hydrate) → edit_resend → reset → undo, asserting
// the chat state and the BFF calls at each step.
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { FormatEnvelope, FormatKind } from '@/types/envelope'
import type { StreamHandlers } from '@/services/irisStream'

vi.mock('@/services/irisStream', () => ({
  irisStream: {
    createSession: vi.fn().mockResolvedValue({
      sessionId: 's-1', userId: 'u', tenantId: 't', turns: [],
      createdAt: 'now', updatedAt: 'now',
      staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
    }),
    listSessions: vi.fn().mockResolvedValue([]),
    getSession: vi.fn(),
    getSessionTurn: vi.fn(),
    editResend: vi.fn(),
    resetSession: vi.fn(),
    undoSession: vi.fn(),
  },
}))

import { irisStream } from '@/services/irisStream'
import { useAgentSession } from '@/composables/useAgentSession'
import type { SessionDto, TurnPointerDto } from '@/types/agent-responses'

const blank = (sessionId: string, turns: TurnPointerDto[] = []): SessionDto => ({
  sessionId, userId: 'u', tenantId: 't', entityContext: null, turns,
  createdAt: 'now', updatedAt: 'now',
  staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
})

function envJson(text: string): unknown {
  return FormatEnvelope.toJSON(
    FormatEnvelope.create({
      bubbleId: 'b', turnId: 't', threadId: 'th',
      text, format: { kind: FormatKind.MARKDOWN }, createdAt: 'now', agentVersion: 'g',
    }),
  )
}

const turnPointer = (turnId: string, question: string) => ({
  turnId, agentId: 'golem-v2', question,
  artifactRef: null, displayedBlockIds: [], status: 'done', origin: 'user', createdAt: 'now',
})

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('Stage 2.3 daily-driver flow', () => {
  it('switch → edit_resend → reset → undo', async () => {
    const session = useAgentSession()
    const store = session.chatStore

    // ---- switch to s-7, which has one completed turn (hydrated) ----
    vi.mocked(irisStream.getSession).mockResolvedValue(blank('s-7', [turnPointer('turn-1', 'kolik faktur?')]))
    vi.mocked(irisStream.getSessionTurn).mockResolvedValue({
      turnId: 'turn-1', agentId: 'golem-v2', status: 'done', envelope: envJson('42 faktur'),
    })
    store.streaming = false
    await session.switchSession('s-7')

    expect(session.sessionId.value).toBe('s-7')
    expect(store.messages.map((m) => m.content)).toEqual(['kolik faktur?', '42 faktur'])
    const userBubble = store.messages.find((m) => m.role === 'user')!
    expect(userBubble.turnId).toBe('turn-1')

    // ---- edit-and-resend the hydrated question ----
    vi.mocked(irisStream.editResend).mockImplementation(async (_req, h: StreamHandlers) => {
      h.onTurnId?.('turn-2')
      h.onEnvelope?.(
        FormatEnvelope.create({
          bubbleId: 'b2', turnId: 'turn-2', threadId: 'th',
          text: '7 faktur', format: { kind: FormatKind.MARKDOWN }, createdAt: 'now', agentVersion: 'g',
        }),
      )
    })
    await session.editAndResend(userBubble.id, 'kolik faktur za červen?')

    expect(irisStream.editResend).toHaveBeenCalledWith(
      { sessionId: 's-7', fromTurnId: 'turn-1', editedQuestion: 'kolik faktur za červen?' },
      expect.anything(),
    )
    expect(store.messages.map((m) => m.content)).toContain('7 faktur')
    expect(store.messages.map((m) => m.content)).not.toContain('42 faktur')

    // ---- /reset: server clear + arm undo ----
    vi.mocked(irisStream.resetSession).mockResolvedValue(blank('s-7'))
    await session.resetCurrentSession()
    expect(irisStream.resetSession).toHaveBeenCalledWith('s-7')
    expect(session.lastResetUndoable.value).toBe(true)
    expect(store.messages.every((m) => m.role === 'assistant')).toBe(true) // welcome only

    // ---- undo: restore the snapshot + re-hydrate ----
    vi.mocked(irisStream.undoSession).mockResolvedValue(blank('s-7', [turnPointer('turn-2', 'kolik faktur za červen?')]))
    vi.mocked(irisStream.getSession).mockResolvedValue(blank('s-7', [turnPointer('turn-2', 'kolik faktur za červen?')]))
    vi.mocked(irisStream.getSessionTurn).mockResolvedValue({
      turnId: 'turn-2', agentId: 'golem-v2', status: 'done', envelope: envJson('7 faktur'),
    })
    await session.undoLastReset()

    expect(irisStream.undoSession).toHaveBeenCalledWith('s-7')
    expect(session.lastResetUndoable.value).toBe(false)
    expect(store.messages.map((m) => m.content)).toEqual(['kolik faktur za červen?', '7 faktur'])
  })
})
