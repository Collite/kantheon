// Iris Phase 2 Stage 2.3 T3 — edit-and-resend via the typed-action channel.
// editAndResend discards the tail after the edited bubble (optimistic), streams
// the re-run through POST /v1/action, and replaces the tail with the new answer.
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
    editResend: vi.fn(),
    turn: vi.fn(),
    listSessions: vi.fn().mockResolvedValue([]),
  },
}))

import { irisStream } from '@/services/irisStream'
import { useAgentSession } from '@/composables/useAgentSession'

function answerEnvelope(text: string): FormatEnvelope {
  return FormatEnvelope.create({
    bubbleId: 'b-new', turnId: 'turn-2', threadId: 'th',
    text,
    format: { kind: FormatKind.MARKDOWN },
    createdAt: 'now', agentVersion: 'g',
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(irisStream.editResend).mockReset()
  vi.mocked(irisStream.turn).mockReset()
})

describe('useAgentSession — editAndResend', () => {
  it('discards the tail, streams /v1/action, and lands the re-run answer', async () => {
    const session = useAgentSession()
    const store = session.chatStore
    store.clear()
    session.sessionId.value = 's-1'
    // A completed turn: user bubble (with server turnId) + assistant answer.
    const userMsg = store.addUserMessage('kolik mám tržeb v lednu?')
    userMsg.turnId = 'turn-1'
    const oldAnswer = store.beginAssistantTurn()
    oldAnswer.turnId = 'turn-1'
    oldAnswer.content = 'Leden: 100'
    store.finalizeAssistantTurn()

    vi.mocked(irisStream.editResend).mockImplementation(
      async (_req, handlers: StreamHandlers) => {
        handlers.onTurnId?.('turn-2')
        handlers.onEnvelope?.(answerEnvelope('Březen: 250'))
      },
    )

    await session.editAndResend(userMsg.id, 'kolik mám tržeb v březnu?')

    // The typed-action carried the edited question + the prior turn id.
    expect(irisStream.editResend).toHaveBeenCalledWith(
      { sessionId: 's-1', fromTurnId: 'turn-1', editedQuestion: 'kolik mám tržeb v březnu?' },
      expect.anything(),
    )
    // The old answer is gone; the new one is present; nothing is left greyed.
    const texts = store.messages.map((m) => m.content)
    expect(texts).toContain('Březen: 250')
    expect(texts).not.toContain('Leden: 100')
    expect(store.messages.some((m) => m.discarded)).toBe(false)
    expect(store.streaming).toBe(false)
  })

  it('falls back to a plain turn when the edited bubble has no server turnId', async () => {
    const session = useAgentSession()
    const store = session.chatStore
    store.clear()
    session.sessionId.value = 's-1'
    const userMsg = store.addUserMessage('q') // no turnId (never finalized server-side)
    store.beginAssistantTurn()
    store.finalizeAssistantTurn()
    vi.mocked(irisStream.turn).mockResolvedValue(answerEnvelope('plain re-run'))

    await session.editAndResend(userMsg.id, 'edited q')

    expect(irisStream.turn).toHaveBeenCalledWith({ sessionId: 's-1', question: 'edited q' })
    expect(irisStream.editResend).not.toHaveBeenCalled()
    expect(store.messages.some((m) => m.content === 'plain re-run')).toBe(true)
  })

  it('restores the discarded tail when the re-run errors', async () => {
    const session = useAgentSession()
    const store = session.chatStore
    store.clear()
    session.sessionId.value = 's-1'
    const userMsg = store.addUserMessage('keep me')
    userMsg.turnId = 'turn-1'
    const oldAnswer = store.beginAssistantTurn()
    oldAnswer.content = 'old answer'
    oldAnswer.turnId = 'turn-1'
    store.finalizeAssistantTurn()

    vi.mocked(irisStream.editResend).mockRejectedValue(new Error('stream blew up'))

    await expect(session.editAndResend(userMsg.id, 'new q')).rejects.toThrow('stream blew up')
    // The original conversation is restored intact.
    expect(store.messages.some((m) => m.content === 'old answer')).toBe(true)
    expect(store.messages.some((m) => m.discarded)).toBe(false)
    expect(store.streaming).toBe(false)
  })
})
