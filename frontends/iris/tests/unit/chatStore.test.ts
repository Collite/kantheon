// FE-4.6 — Unit tests for Phase 4 edit & resend actions in chatStore.
// Tests: discardAfter, removeDiscarded, restoreDiscarded, clearPendingDiscard.
import { setActivePinia, createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useChatStore } from '@/stores/chatStore'
import { FormatEnvelope, FormatKind } from '@/types/envelope'

// chatStore has no service dependency; mock the BFF client as a no-op so the
// store can be tested in isolation without importing it.
vi.mock('@/services/irisStream', () => ({ irisStream: {} }))

describe('Phase 4 — edit & resend actions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const makeMsg = (id: string, role: 'user' | 'assistant', content = 'test') => ({
    id,
    role,
    content,
    timestamp: new Date(),
  })

  describe('discardAfter', () => {
    it('marks messages after the given id as discarded', () => {
      const store = useChatStore()
      store.messages = [
        makeMsg('u1', 'user', 'hello'),
        makeMsg('a1', 'assistant', 'there'),
        makeMsg('u2', 'user', 'another'),
        makeMsg('a2', 'assistant', 'response'),
      ]

      store.discardAfter('u2')

      const u2 = store.messages.find((m) => m.id === 'u2')
      const a2 = store.messages.find((m) => m.id === 'a2')
      expect(u2?.discarded).toBeUndefined()
      expect(a2?.discarded).toBe(true)
    })

    it('saves pending snapshot for the given id', () => {
      const store = useChatStore()
      store.messages = [
        makeMsg('u1', 'user'),
        makeMsg('a1', 'assistant'),
      ]

      store.discardAfter('u1')

      expect(store.pendingDiscard['u1']).toBeDefined()
      expect(store.pendingDiscard['u1']).toHaveLength(2)
    })

    it('is idempotent — calling twice replaces the snapshot', () => {
      const store = useChatStore()
      store.messages = [
        makeMsg('u1', 'user'),
        makeMsg('a1', 'assistant'),
        makeMsg('u2', 'user'),
      ]

      store.discardAfter('u1')
      const snap1 = store.pendingDiscard['u1']

      store.discardAfter('u1')
      const snap2 = store.pendingDiscard['u1']

      expect(snap1).not.toBe(snap2)
      expect(snap1).toHaveLength(3)
      expect(snap2).toHaveLength(3)
    })

    it('does nothing if message id not found', () => {
      const store = useChatStore()
      store.messages = [makeMsg('u1', 'user')]

      store.discardAfter('nonexistent')

      expect(store.pendingDiscard['nonexistent']).toBeUndefined()
    })
  })

  describe('restoreDiscarded', () => {
    it('restores messages from snapshot and clears pending', () => {
      const store = useChatStore()
      store.messages = [
        makeMsg('u1', 'user', 'hello'),
        makeMsg('a1', 'assistant', 'there'),
        makeMsg('u2', 'user', 'another'),
      ]
      store.discardAfter('u1')

      store.restoreDiscarded('u1')

      expect(store.messages).toHaveLength(3)
      const restored = store.messages.find((m) => m.id === 'a1')
      expect(restored?.discarded).toBeUndefined()
      expect(store.pendingDiscard['u1']).toBeUndefined()
    })

    it('does nothing if no pending snapshot', () => {
      const store = useChatStore()
      store.messages = [makeMsg('u1', 'user')]

      store.restoreDiscarded('u1')

      expect(store.messages).toHaveLength(1)
    })
  })

  describe('removeDiscarded', () => {
    it('splices out messages flagged discarded after the given id (spec §6.3)', () => {
      const store = useChatStore()
      store.messages = [
        makeMsg('u1', 'user', 'first'),
        makeMsg('a1', 'assistant', 'reply 1'),
        makeMsg('u2', 'user', 'second'),
        makeMsg('a2', 'assistant', 'reply 2'),
      ]
      store.discardAfter('u1') // flags a1, u2, a2

      store.removeDiscarded('u1')

      // Only the pre-edit prefix survives; discarded entries are gone.
      expect(store.messages).toHaveLength(1)
      expect(store.messages[0]!.id).toBe('u1')
    })

    it('preserves messages appended after discardAfter that are not flagged (e.g. the new agent bubble)', () => {
      const store = useChatStore()
      store.messages = [
        makeMsg('u1', 'user'),
        makeMsg('a1', 'assistant'),
      ]
      store.discardAfter('u1') // flags a1
      // simulate beginAssistantTurn appending a fresh bubble after the discard
      store.messages.push(makeMsg('a_new', 'assistant', 'fresh'))

      store.removeDiscarded('u1')

      expect(store.messages.map((m) => m.id)).toEqual(['u1', 'a_new'])
    })

    it('is a no-op if the id is not found', () => {
      const store = useChatStore()
      store.messages = [makeMsg('u1', 'user'), makeMsg('a1', 'assistant')]
      store.removeDiscarded('nonexistent')
      expect(store.messages).toHaveLength(2)
    })
  })

  describe('appendOrReplaceEnvelope — concurrent stream protection', () => {
    // Stage 07 — envelope shape widened to v2 (turn_id / thread_id / plan_source
    // / plan_score / agent_version are required).
    const makeEnvelope = (bubbleId: string, text: string) =>
      FormatEnvelope.create({
        bubbleId,
        turnId: 't',
        threadId: 'th',
        text,
        format: { kind: FormatKind.PLAINTEXT },
        createdAt: new Date().toISOString(),
        agentVersion: 'golem-v2.0.0',
      })

    it('attaches to the streaming bubble by default (the common Phase 3 path)', () => {
      const store = useChatStore()
      store.beginAssistantTurn() // creates an empty assistant bubble, sets streamingAssistantId
      const streamingId = store.streamingAssistantId
      expect(streamingId).not.toBeNull()

      store.appendOrReplaceEnvelope(makeEnvelope('bubble-x', 'hello'))

      // Default: attaches to the streaming bubble — same id, now has envelope.
      expect(store.messages).toHaveLength(1)
      expect(store.messages[0]!.id).toBe(streamingId)
      expect(store.messages[0]!.envelope?.bubbleId).toBe('bubble-x')
    })

    it('appends a fresh bubble when attachToStreaming=false (typed action during concurrent stream)', () => {
      const store = useChatStore()
      // Simulate a normal /chat/stream that already begun a turn.
      store.beginAssistantTurn()
      const normalStreamId = store.streamingAssistantId
      expect(normalStreamId).not.toBeNull()

      // A typed-action (not owning the turn) emits an envelope. Must not
      // overwrite the normal stream's bubble.
      store.appendOrReplaceEnvelope(
        makeEnvelope('typed-action-bubble', 'OK, vybráno: Acme'),
        { attachToStreaming: false },
      )

      expect(store.messages).toHaveLength(2)
      // Normal stream's bubble: still empty, no envelope.
      const normalBubble = store.messages.find((m) => m.id === normalStreamId)
      expect(normalBubble?.envelope).toBeUndefined()
      expect(normalBubble?.content).toBe('')
      // Fresh bubble carries the typed-action envelope.
      const newBubble = store.messages.find((m) => m.envelope?.bubbleId === 'typed-action-bubble')
      expect(newBubble).toBeDefined()
      expect(newBubble?.content).toBe('OK, vybráno: Acme')

      // streamingAssistantId still points at the normal stream — typed action
      // didn't clobber it.
      expect(store.streamingAssistantId).toBe(normalStreamId)
    })

    it('still replaces in place when bubble_id matches an existing message', () => {
      const store = useChatStore()
      store.messages = [
        {
          id: 'm1',
          role: 'assistant',
          content: 'old',
          timestamp: new Date(),
          envelope: makeEnvelope('bubble-x', 'old') as never,
        },
      ]

      store.appendOrReplaceEnvelope(
        makeEnvelope('bubble-x', 'new'),
        { attachToStreaming: false }, // even with attachToStreaming=false, bubble_id match wins
      )

      expect(store.messages).toHaveLength(1)
      expect(store.messages[0]!.envelope?.text).toBe('new')
      expect(store.messages[0]!.content).toBe('new')
    })
  })

  describe('clearPendingDiscard', () => {
    it('deletes the pending snapshot', () => {
      const store = useChatStore()
      store.messages = [makeMsg('u1', 'user'), makeMsg('a1', 'assistant')]
      store.discardAfter('u1')

      store.clearPendingDiscard('u1')

      expect(store.pendingDiscard['u1']).toBeUndefined()
      expect(store.messages).toHaveLength(2)
    })
  })
})