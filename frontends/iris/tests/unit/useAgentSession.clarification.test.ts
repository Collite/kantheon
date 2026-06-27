// Stage clarif-resume 01 (T6) — D2 regression from the FE side.
//
// A clarification turn arrives as an envelope with EMPTY `chips` and a
// `pendingClarification` payload. The dynamic-chip store must stay untouched:
// options are the ClarificationCard's job, never the chip strip.
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { FormatEnvelope, FormatKind } from '@/types/envelope'
import type { StreamHandlers } from '@/services/irisStream'
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
import { useChipStore } from '@/stores/chipStore'

function clarificationEnvelope(): FormatEnvelope {
  return FormatEnvelope.create({
    bubbleId: 'b', turnId: 't', threadId: 'th',
    text: 'Upřesněte prosím výběr.',
    format: { kind: FormatKind.MARKDOWN },
    chips: [], // D2 — clarification turns carry no chips
    pendingClarification: {
      kind: 'entity_choice',
      resumeToken: 'tok',
      options: [
        { id: 'ent_1', display: 'EX KANCELAR' },
        { id: 'ent_2', display: 'VY KANCELAR' },
      ],
    },
    createdAt: 'now', agentVersion: 'g',
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(irisStream.streamTurn).mockReset()
})

describe('useAgentSession clarification turn (D2)', () => {
  it('leaves dynamicChips empty when a clarification envelope arrives', async () => {
    vi.mocked(irisStream.streamTurn).mockImplementation(
      async (_req: ChatTurnRequestDto, handlers: StreamHandlers) => {
        handlers.onEnvelope?.(clarificationEnvelope())
      },
    )

    const session = useAgentSession()
    const chipStore = useChipStore()
    chipStore.setDynamicChips([]) // start clean
    // useAgentSession is a module singleton; neutralise streaming state that
    // may leak in from another test so sendMessage doesn't early-return.
    session.chatStore.streaming = false

    session.prompt.value = 'Kancelář'
    await session.sendMessage()

    // The turn actually ran (guard is meaningful: onEnvelope executed) but no
    // chips were published — the clarification options stay off the chip strip.
    expect(irisStream.streamTurn).toHaveBeenCalledOnce()
    expect(chipStore.dynamicChips).toEqual([])
  })
})
