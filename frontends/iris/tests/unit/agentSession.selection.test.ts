// Phase 2 Stage 2.2 — the armed row-detail selection is FE-only UI state. The
// BFF v1 turn request has no `selection` field (typed actions ride /v1/action in
// Phase 3), so sendMessage transmits NO selection — it only resets the one-shot
// armed state. This guards those one-shot semantics across the BFF re-point.
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatTurnRequestDto } from '@/types/agent-responses'

vi.mock('@/services/irisStream', () => ({
  irisStream: {
    createSession: vi.fn().mockResolvedValue({
      sessionId: 's-1', userId: 'u', tenantId: 't', turns: [],
      createdAt: 'now', updatedAt: 'now',
      staticChips: [], exampleQuestions: [], packages: [], agentVersion: 'g',
    }),
    streamTurn: vi.fn(),
  },
}))

import { irisStream } from '@/services/irisStream'
import { useAgentSession } from '@/composables/useAgentSession'

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(irisStream.streamTurn).mockReset()
  vi.mocked(irisStream.streamTurn).mockResolvedValue(undefined)
})

function lastRequest(): ChatTurnRequestDto {
  const calls = vi.mocked(irisStream.streamTurn).mock.calls
  return calls[calls.length - 1]![0] as ChatTurnRequestDto
}

describe('useAgentSession — row-detail selection', () => {
  it('armSelection sets armedSelection state', () => {
    const session = useAgentSession()
    session.clearSelection()
    expect(session.armedSelection.value).toBeNull()
    session.armSelection({ bubble_id: 'b1', row_indices: [2] })
    expect(session.armedSelection.value).toEqual({ bubble_id: 'b1', row_indices: [2] })
  })

  it('sendMessage clears the armed selection (one-shot) and sends none on the wire', async () => {
    const session = useAgentSession()
    session.chatStore.streaming = false
    session.armSelection({ bubble_id: 'b1', row_indices: [0] })
    session.prompt.value = 'why is this one so high?'

    await session.sendMessage()

    expect(irisStream.streamTurn).toHaveBeenCalledOnce()
    // BFF turn request is { sessionId, question, desiredFormat? } — no selection.
    expect(lastRequest()).not.toHaveProperty('selection')
    expect(lastRequest().question).toBe('why is this one so high?')
    // One-shot: the armed selection drops the moment the turn fires.
    expect(session.armedSelection.value).toBeNull()
  })

  it('a normal message sends a clean turn request (no selection)', async () => {
    const session = useAgentSession()
    session.chatStore.streaming = false
    session.clearSelection()
    session.prompt.value = 'a plain question'

    await session.sendMessage()

    expect(irisStream.streamTurn).toHaveBeenCalledOnce()
    expect(lastRequest()).not.toHaveProperty('selection')
  })
})
