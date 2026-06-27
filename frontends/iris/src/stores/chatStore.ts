// Phase 3 chatStore — owns the chat history and streaming lifecycle.
//
// Each `messages[i]` is the rendered envelope plus a per-bubble client-side
// `displayState` overlay (architecture.md §2.3). The agent emits the format
// envelope through `appendOrReplaceEnvelope()`; user-driven display tweaks
// (Phase 5/6: hide column, change chart type, etc.) mutate `displayState`
// only — they don't round-trip to the BE in v2.
//
// Phase 3 supports plaintext + markdown only. The store doesn't care which
// kind a bubble is; renderers do that via `formatCatalog`.
//
// Phase 4: edit & resend — `discardAfter(messageId)` greys out messages
// after the edited one; `restoreDiscarded(messageId)` undoes it on error.
import { defineStore } from 'pinia'
import { ref, toRaw } from 'vue'
import { FormatKind } from '@/types/envelope'
import type { DisplayState, FormatEnvelope } from '@/types/envelope'

export type ChatRole = 'user' | 'assistant'

export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  timestamp: Date
  options?: string[]
  envelope?: FormatEnvelope
  displayState?: DisplayState
  // Phase 4: true when greyed out because the user edited a prior message
  discarded?: boolean
  // Stage 2.3: the server turn this bubble belongs to. Set on live turns (from
  // the stream's turnId) and on hydration (from the turn pointer); names
  // `fromTurnId` for an `edit_resend`. Undefined for client-only bubbles
  // (welcome / help / refresh).
  turnId?: string
}

const keyGen = () => Math.random().toString(36).substring(7)

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const streamingAssistantId = ref<string | null>(null)
  const streaming = ref(false)
  const currentNode = ref<string | null>(null)

  // Phase 4: pending discard — stores snapshot of messages before discard
  // so we can restore on error. Keyed by the edited message id.
  const pendingDiscard = ref<Record<string, ChatMessage[]>>({})

  // Phase 4: only one edit at a time — tracks which message is being edited.
  const currentlyEditingId = ref<string | null>(null)

  const addUserMessage = (content: string): ChatMessage => {
    const msg: ChatMessage = {
      id: keyGen(),
      role: 'user',
      content,
      timestamp: new Date(),
    }
    messages.value.push(msg)
    return msg
  }

  const beginAssistantTurn = (): ChatMessage => {
    const msg: ChatMessage = {
      id: keyGen(),
      role: 'assistant',
      content: '',
      timestamp: new Date(),
    }
    messages.value.push(msg)
    streamingAssistantId.value = msg.id
    streaming.value = true
    return msg
  }

  const appendChunkToCurrentAssistant = (chunk: string) => {
    const id = streamingAssistantId.value
    if (!id) return
    const msg = messages.value.find((m) => m.id === id)
    if (msg) msg.content += chunk
  }

  const setOptionsOnCurrentAssistant = (options?: string[]) => {
    const id = streamingAssistantId.value
    if (!id) return
    if (!options || options.length === 0) return
    const msg = messages.value.find((m) => m.id === id)
    if (msg) msg.options = options
  }

  /** Attach (or replace) an envelope on a bubble.
   *
   * If a bubble already exists with a matching `bubble_id`, that bubble is
   * updated in place — important once Phase v2.1 typed actions re-emit a
   * bubble for sort/filter/paginate.
   *
   * Otherwise: when `attachToStreaming` is true (default — the common Phase 3
   * path) the envelope attaches to the current streaming bubble. The caller
   * must own that streaming bubble (i.e. it called `beginAssistantTurn` for
   * this turn). A typed-action dispatched while another turn's stream is
   * in-flight must pass `attachToStreaming: false` so the envelope appends a
   * fresh bubble instead of overwriting the unrelated stream's bubble.
   */
  const appendOrReplaceEnvelope = (
    envelope: FormatEnvelope,
    opts: { attachToStreaming?: boolean } = {},
  ) => {
    const attachToStreaming = opts.attachToStreaming ?? true
    const existing = messages.value.find((m) => m.envelope?.bubbleId === envelope.bubbleId)
    if (existing) {
      existing.envelope = envelope
      existing.content = envelope.text ?? existing.content
      return
    }

    if (attachToStreaming) {
      const id = streamingAssistantId.value
      const target = id ? messages.value.find((m) => m.id === id) : undefined
      if (target) {
        target.envelope = envelope
        target.content = envelope.text ?? target.content
        // Stage 3.2: envelope/v1 chips (prompt|routing|investigate) render via
        // ChipStrip off `envelope.chips` — no longer flattened onto `options`
        // (which would lose the routing/investigate arms and double-render the
        // prompt arm). `options` stays for explicit, non-envelope option lists.
        return
      }
    }

    // No streaming bubble (or caller doesn't own it) — append a fresh assistant message.
    messages.value.push({
      id: keyGen(),
      role: 'assistant',
      content: envelope.text ?? '',
      timestamp: new Date(),
      envelope,
    })
  }

  const finalizeAssistantTurn = (options?: string[]) => {
    setOptionsOnCurrentAssistant(options)
    streamingAssistantId.value = null
    streaming.value = false
    currentNode.value = null
  }

  const setCurrentNode = (node: string | null) => {
    currentNode.value = node
  }

  /** Phase 6: merge a partial `displayState` into a chat bubble. Used for
   * client-side view swaps (Show-as-Chart on a table, Show-as-Table on a
   * chart) and any per-bubble display tweaks that don't round-trip to the
   * BE in v2 (column visibility, hidden series, etc.). */
  const updateDisplayState = (messageId: string, partial: DisplayState) => {
    const msg = messages.value.find((m) => m.id === messageId)
    if (!msg) return
    msg.displayState = { ...msg.displayState, ...partial }
  }

  const clear = () => {
    messages.value = []
    streamingAssistantId.value = null
    streaming.value = false
    currentNode.value = null
  }

  /** Stage 2.3: replace the whole conversation in one shot — used by history
   * hydration (server turns → bubbles on session switch / reload) and by undo
   * re-hydration. Resets the streaming lifecycle like `clear`. */
  const setMessages = (msgs: ChatMessage[]) => {
    messages.value = msgs
    streamingAssistantId.value = null
    streaming.value = false
    currentNode.value = null
  }

  /** Resolve the format kind for a message — used by ChatBubble to look up
   * the renderer in `formatCatalog`. Phase 6: a per-bubble `displayState`
   * `viewKind` overrides the agent-emitted kind so users can flip a table
   * to a chart (and back) without touching the envelope. */
  const formatKindFor = (message: ChatMessage): FormatKind => {
    const override = (message.displayState as { viewKind?: FormatKind } | undefined)?.viewKind
    return override ?? message.envelope?.format?.kind ?? FormatKind.PLAINTEXT
  }

  // Phase 4: edit & resend — discard messages after an edited user message
  /** Save a snapshot of all messages and mark everything after
   * `afterMessageId` as `discarded=true`. Idempotent — calling again
   * before a finalization replaces the pending snapshot. */
  const discardAfter = (afterMessageId: string) => {
    const idx = messages.value.findIndex((m) => m.id === afterMessageId)
    if (idx === -1) return
    pendingDiscard.value[afterMessageId] = structuredClone(toRaw(messages.value))
    for (let i = idx + 1; i < messages.value.length; i++) {
      const msg = messages.value[i]
      if (msg) msg.discarded = true
    }
  }

  /** Splice discarded messages (after `afterMessageId`) out of the
   * conversation. Called on a successful edit-and-resend, per spec §6.3:
   * "On agent response: the discarded messages are removed from the
   * conversation." Messages appended *after* `discardAfter` was called
   * (e.g. the new assistant bubble for the re-run) are preserved — only
   * messages flagged `discarded` are removed. */
  const removeDiscarded = (afterMessageId: string) => {
    const idx = messages.value.findIndex((m) => m.id === afterMessageId)
    if (idx === -1) return
    messages.value = messages.value.filter((m, i) => i <= idx || !m.discarded)
  }

  /** Restore messages to the pre-discard snapshot (call on error after
   * `discardAfter`). Clears the pending snapshot. */
  const restoreDiscarded = (afterMessageId: string) => {
    const snapshot = pendingDiscard.value[afterMessageId]
    if (!snapshot) return
    messages.value = snapshot
    delete pendingDiscard.value[afterMessageId]
  }

  /** Clear the pending snapshot after a successful edit (messages already
   * replaced by the agent's new response). */
  const clearPendingDiscard = (afterMessageId: string) => {
    delete pendingDiscard.value[afterMessageId]
  }

  return {
    messages,
    streamingAssistantId,
    streaming,
    currentNode,
    pendingDiscard,
    currentlyEditingId,
    addUserMessage,
    beginAssistantTurn,
    appendChunkToCurrentAssistant,
    setOptionsOnCurrentAssistant,
    appendOrReplaceEnvelope,
    finalizeAssistantTurn,
    setCurrentNode,
    updateDisplayState,
    clear,
    setMessages,
    formatKindFor,
    discardAfter,
    removeDiscarded,
    restoreDiscarded,
    clearPendingDiscard,
  }
})
