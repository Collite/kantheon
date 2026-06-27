// Iris Phase 2 Stage 2.3 — multi-session surface on the composable:
// list / hydrate / switch / reset+undo. History hydration maps server turns
// (GET /v1/session/{id} + per-turn envelopes) back into chat bubbles tagged
// with their server turnId.
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { FormatEnvelope, FormatKind } from '@/types/envelope'

vi.mock('@/services/irisStream', () => ({
  irisStream: {
    createSession: vi.fn().mockResolvedValue({
      sessionId: 's-1', userId: 'u', tenantId: 't', turns: [],
      createdAt: 'now', updatedAt: 'now',
      staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
    }),
    listSessions: vi.fn(),
    getSession: vi.fn(),
    getSessionTurn: vi.fn(),
    resetSession: vi.fn(),
    undoSession: vi.fn(),
  },
}))

import { irisStream } from '@/services/irisStream'
import { useAgentSession } from '@/composables/useAgentSession'

/** A stored-turn envelope as the BFF would return it (proto-JSON object). */
function envelopeJson(text: string): unknown {
  return FormatEnvelope.toJSON(
    FormatEnvelope.create({
      bubbleId: 'b', turnId: 't', threadId: 'th',
      text,
      format: { kind: FormatKind.MARKDOWN },
      createdAt: 'now', agentVersion: 'g',
    }),
  )
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(irisStream.listSessions).mockReset()
  vi.mocked(irisStream.getSession).mockReset()
  vi.mocked(irisStream.getSessionTurn).mockReset()
  vi.mocked(irisStream.resetSession).mockReset()
  vi.mocked(irisStream.undoSession).mockReset()
})

describe('useAgentSession — multi-session surface', () => {
  it('loadSessions populates the rail from GET /v1/sessions', async () => {
    vi.mocked(irisStream.listSessions).mockResolvedValue([
      { sessionId: 's-1', title: 'kolik mám tržeb?', turnCount: 2, updatedAt: 'now' },
      { sessionId: 's-2', title: 'New session', turnCount: 0, updatedAt: 'before' },
    ])
    const session = useAgentSession()

    await session.loadSessions()

    expect(session.sessions.value.map((s) => s.sessionId)).toEqual(['s-1', 's-2'])
  })

  it('hydrateSession rebuilds user + assistant bubbles tagged with the turnId', async () => {
    vi.mocked(irisStream.getSession).mockResolvedValue({
      sessionId: 's-9', userId: 'u', tenantId: 't', turns: [
        {
          turnId: 'turn-1', agentId: 'golem-v2', question: 'kolik mám faktur?',
          artifactRef: null, displayedBlockIds: ['b1'], status: 'done', origin: 'user', createdAt: 'now',
        },
      ],
      createdAt: 'now', updatedAt: 'now',
      staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
    })
    vi.mocked(irisStream.getSessionTurn).mockResolvedValue({
      turnId: 'turn-1', agentId: 'golem-v2', status: 'done', envelope: envelopeJson('Máte 42 faktur.'),
    })
    const session = useAgentSession()

    await session.hydrateSession('s-9')

    const msgs = session.chatStore.messages
    expect(msgs.map((m) => m.role)).toEqual(['user', 'assistant'])
    expect(msgs[0]?.content).toBe('kolik mám faktur?')
    expect(msgs[1]?.content).toBe('Máte 42 faktur.')
    // Both bubbles carry the server turnId (so edit_resend can name fromTurnId).
    expect(msgs[0]?.turnId).toBe('turn-1')
    expect(msgs[1]?.turnId).toBe('turn-1')
  })

  it('hydrateSession on an empty session shows only the welcome bubble', async () => {
    vi.mocked(irisStream.getSession).mockResolvedValue({
      sessionId: 's-empty', userId: 'u', tenantId: 't', turns: [],
      createdAt: 'now', updatedAt: 'now',
      staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
    })
    const session = useAgentSession()

    await session.hydrateSession('s-empty')

    expect(session.chatStore.messages).toHaveLength(1)
    expect(session.chatStore.messages[0]?.id).toBe('welcome')
    expect(irisStream.getSessionTurn).not.toHaveBeenCalled()
  })

  it('switchSession adopts the id and hydrates it', async () => {
    vi.mocked(irisStream.getSession).mockResolvedValue({
      sessionId: 's-7', userId: 'u', tenantId: 't', turns: [],
      createdAt: 'now', updatedAt: 'now',
      staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
    })
    const session = useAgentSession()
    session.chatStore.streaming = false

    await session.switchSession('s-7')

    expect(session.sessionId.value).toBe('s-7')
    expect(irisStream.getSession).toHaveBeenCalledWith('s-7')
  })

  it('resetCurrentSession clears the chat, arms undo, and resets server turns', async () => {
    vi.mocked(irisStream.resetSession).mockResolvedValue({
      sessionId: 's-1', userId: 'u', tenantId: 't', turns: [],
      createdAt: 'now', updatedAt: 'now',
      staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
    })
    vi.mocked(irisStream.listSessions).mockResolvedValue([])
    const session = useAgentSession()
    session.sessionId.value = 's-1'
    session.chatStore.addUserMessage('something')

    await session.resetCurrentSession()

    expect(irisStream.resetSession).toHaveBeenCalledWith('s-1')
    expect(session.lastResetUndoable.value).toBe(true)
    // welcome only after a reset
    expect(session.chatStore.messages.every((m) => m.role === 'assistant')).toBe(true)
  })

  it('undoLastReset restores the snapshot and re-hydrates', async () => {
    vi.mocked(irisStream.undoSession).mockResolvedValue({
      sessionId: 's-1', userId: 'u', tenantId: 't', turns: [
        {
          turnId: 'turn-1', agentId: 'golem-v2', question: 'restored?',
          artifactRef: null, displayedBlockIds: [], status: 'done', origin: 'user', createdAt: 'now',
        },
      ],
      createdAt: 'now', updatedAt: 'now',
      staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
    })
    vi.mocked(irisStream.getSession).mockResolvedValue({
      sessionId: 's-1', userId: 'u', tenantId: 't', turns: [
        {
          turnId: 'turn-1', agentId: 'golem-v2', question: 'restored?',
          artifactRef: null, displayedBlockIds: [], status: 'done', origin: 'user', createdAt: 'now',
        },
      ],
      createdAt: 'now', updatedAt: 'now',
      staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
    })
    vi.mocked(irisStream.getSessionTurn).mockResolvedValue({
      turnId: 'turn-1', agentId: 'golem-v2', status: 'done', envelope: envelopeJson('back!'),
    })
    vi.mocked(irisStream.listSessions).mockResolvedValue([])
    const session = useAgentSession()
    session.sessionId.value = 's-1'
    session.lastResetUndoable.value = true

    await session.undoLastReset()

    expect(irisStream.undoSession).toHaveBeenCalledWith('s-1')
    expect(session.lastResetUndoable.value).toBe(false)
    expect(session.chatStore.messages.some((m) => m.content === 'restored?')).toBe(true)
  })
})
