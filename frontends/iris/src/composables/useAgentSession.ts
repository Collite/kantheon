// Phase 3: useAgentSession is now a thin coordinator on top of `chatStore`.
//
// chatStore owns messages + streaming lifecycle; this composable still owns
// the agent-graph-side state (currentNode, currentTool, traversedNodes) plus
// the language picker + sessionId. Both ChatPanel and AgentGraphPanel
// import this — the graph panel ignores the chatStore plumbing.
import { ref } from 'vue'
import { config } from '@/config'
import { irisStream, type AgentKey } from '@/services/irisStream'
import {
  toSuggestedChip,
  type SessionSummaryDto,
  type TurnSelection,
} from '@/types/agent-responses'
import { promptChipsOf } from '@/types/chips'
import { typedAction, type FilterOperator } from '@/services/typedAction'
import { FormatEnvelope } from '@/types/envelope'
import type { StreamHandlers } from '@/services/irisStream'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessage } from '@/stores/chatStore'
import { useChipStore } from '@/stores/chipStore'
import { i18n, setLocale } from '@/i18n'

export type Lang = 'en' | 'de' | 'cs' | 'sk' | 'hu'

export interface Translations {
  title: string
  placeholder: string
  send: string
  welcome: string
}

export const defaultTranslations: Record<Lang, Translations> = {
  en: { title: 'ERP Agent', placeholder: 'Ask a question...', send: 'Send', welcome: 'Hello! I am an ERP Agent. Ask me about the data in the ERP system and I will try to help you.' },
  de: { title: 'KI-Agent', placeholder: 'Stellen Sie eine Frage...', send: 'Senden', welcome: 'Hallo! Ich bin ein ERP-Agent. Fragen Sie mich nach den Daten im ERP-System und ich werde versuchen, Ihnen zu helfen.' },
  cs: { title: 'AI Agent', placeholder: 'Zeptejte se...', send: 'Odeslat', welcome: 'Dobrý den! Jsem ERP Agent. Zeptejte se mě na data v ERP systému a já se vám pokusím pomoci.' },
  sk: { title: 'AI Agent', placeholder: 'Opýtajte sa...', send: 'Odoslať', welcome: 'Dobrý deň! Som ERP Agent. Opýtajte sa ma na dáta v ERP systéme a ja sa vám pokúsim pomôcť.' },
  hu: { title: 'AI Agent', placeholder: 'Tegyen fel kérdést...', send: 'Küldés', welcome: 'Üdvözölöm! Én vagyok az ERP Agent. Kérdezzen az ERP rendszer adatairól, és megpróbálok segíteni.' },
}

export interface LangOption { code: Lang; label: string }
export const languages: LangOption[] = [
  { code: 'en', label: '🇬🇧 EN' },
  { code: 'de', label: '🇩🇪 DE' },
  { code: 'cs', label: '🇨🇿 CS' },
  { code: 'sk', label: '🇸🇰 SK' },
  { code: 'hu', label: '🇭🇺 HU' },
]

const keyGen = () => Math.random().toString(36).substring(7)

// Happy-path successor map for the v2 cascade graph. Mirrors the edges in
// agents/golem/src/agent/graph_v2.py. Conditional edges resolve to whichever
// node the FE should highlight while the backend processes — `extract_entities`
// can short-circuit to `awaiting_clarification`, but the common case is
// `classify_and_plan`, so that's what the UI optimistically advances to.
// Used by `onNodeDone` to advance the active-node highlight to the next node
// before it actually starts running (which can be many seconds away).
const NEXT_NODE_HAPPY_PATH: Record<string, string | null> = {
  bootstrap: 'resolve_selection',
  resolve_selection: 'extract_entities',
  extract_entities: 'classify_and_plan',
  classify_and_plan: 'pick_plan',
  pick_plan: 'execute',
  execute: 'format',
  format: 'update_state',
  update_state: null,
  awaiting_clarification: null,
}

// Default to the first agent in the configured registry (VITE_GOLEM_AGENTS). No hardcoded id.
const agentKey = ref<AgentKey>(config.golemAgents[0]?.id ?? '')
const sessionId = ref(keyGen())
const selectedLang = ref<Lang>('en')

const prompt = ref('')
const agentStatus = ref<string | null>(null)

// Phase 7: one-shot pre-send flags armed by the slash-command pipeline.
// Cleared the moment a chat turn fires.
const desiredFormat = ref<string | null>(null)
const dryRunNext = ref(false)

// Row-detail selection — armed by ChatBubble when the user picks "Show detail"
// on a table row. Rides along with the next turn (whatever text the user
// submits) and is cleared the moment that turn fires — one-shot, like the
// pre-send flags above.
const armedSelection = ref<TurnSelection | null>(null)
const armSelection = (s: TurnSelection) => {
  armedSelection.value = s
}
const clearSelection = () => {
  armedSelection.value = null
}

// Graph-side state (kept here so AgentGraphPanel doesn't need to import the
// chatStore — it only cares about which logic node + MCP tool are active).
const currentNode = ref<string | null>(null)
const currentTool = ref<{ tool_name: string; server: string } | null>(null)
const traversedNodes = ref<string[]>([])

const customTitle = ref<Record<Lang, string> | null>(null)

// Surface the BFF discovery fields (POST /v1/session) so the view can render
// example questions as ghost-text and show the agent version somewhere.
const exampleQuestions = ref<string[]>([])
const packageNames = ref<string[]>([])
const agentVersion = ref<string | null>(null)

let initialised = false
let chatStore: ReturnType<typeof useChatStore> | null = null

const ensureChatStore = () => {
  if (!chatStore) chatStore = useChatStore()
  return chatStore
}

const setWelcomeBubble = (lang: Lang) => {
  const welcome = defaultTranslations[lang]?.welcome
  if (!welcome) return
  const store = ensureChatStore()
  if (store.messages.length === 0) {
    store.messages.push({
      id: 'welcome',
      role: 'assistant',
      content: welcome,
      timestamp: new Date(),
    })
  } else {
    const first = store.messages[0]
    if (first?.id === 'welcome') first.content = welcome
  }
}

const init = async (key: AgentKey, customTitleMap?: Record<Lang, string>) => {
  // Switching to a different agent: reset the conversation + session so the new
  // agent (a different Golem package set) starts clean. The composable is a
  // module-level singleton, so without this the prior agent's chat would leak.
  const switchingAgent = initialised && agentKey.value !== key
  agentKey.value = key
  customTitle.value = customTitleMap ?? null

  if (initialised && !switchingAgent) return
  initialised = true

  const store = ensureChatStore()
  if (switchingAgent) {
    sessionId.value = keyGen()
    store.clear()
    traversedNodes.value = []
    currentNode.value = null
    currentTool.value = null
  }

  const stored = localStorage.getItem(`agent-lang-${key}`) as Lang | null
  const loaded = stored && languages.some(l => l.code === stored) ? stored : 'cs'
  selectedLang.value = loaded
  setLocale(loaded)

  // Stage 2.2 — bootstrap against the BFF. POST /v1/session mints a server
  // session and carries the discovery surface (static chips, example questions,
  // packages, agent version). We adopt the server-assigned session id.
  const chipStore = useChipStore()
  try {
    const session = await irisStream.createSession()
    sessionId.value = session.sessionId
    chipStore.setStaticChips(session.staticChips.map(toSuggestedChip))
    exampleQuestions.value = session.exampleQuestions
    packageNames.value = session.packages
    agentVersion.value = session.agentVersion
  } catch (err) {
    console.warn('[useAgentSession] createSession failed', err)
    chipStore.setStaticChips([])
  }

  setWelcomeBubble(loaded)
}

const changeLanguage = async (lang: Lang) => {
  selectedLang.value = lang
  localStorage.setItem(`agent-lang-${agentKey.value}`, lang)
  setLocale(lang)

  // Stage 2.2 — locale is a UI concern now. The BFF POST /v1/session takes no
  // locale and is non-idempotent (it mints a new session), so re-bootstrapping
  // here would drop the live conversation. Locale-aware discovery returns with
  // the dedicated /v1/discover surface (Iris Phase 4); until then the existing
  // chip catalogue carries across a locale switch unchanged.
  setWelcomeBubble(lang)
}

const handleOptionClick = (opt: string) => {
  prompt.value = opt
}

const sendMessage = async () => {
  const store = ensureChatStore()
  if (!prompt.value.trim() || store.streaming) return

  const userText = prompt.value
  prompt.value = ''

  // One-shot per-turn switches (slash-command pipeline) — never outlive a single
  // turn. `desiredFormat` (/format) rides the BFF turn request; `dryRun` (/sql)
  // and the armed row-detail `selection` have no v1 turn-request field yet (they
  // ride typed-actions in Iris Phase 3), so they are reset but not transmitted.
  const desiredFmt = desiredFormat.value ?? undefined
  dryRunNext.value = false
  desiredFormat.value = null
  armedSelection.value = null

  const userMsg = store.addUserMessage(userText)

  traversedNodes.value = []
  currentNode.value = null
  currentTool.value = null

  const assistantMsg = store.beginAssistantTurn()

  try {
    await irisStream.streamTurn(
      {
        sessionId: sessionId.value,
        question: userText,
        desiredFormat: desiredFmt,
      },
      {
        // Tag both bubbles with the server turn id so a later edit_resend can
        // name `fromTurnId` even for a turn that was never reloaded.
        onTurnId: (turnId) => {
          userMsg.turnId = turnId
          assistantMsg.turnId = turnId
        },
        // The BFF maps the golem cascade onto iris/v1 StepEvents:
        //   node_start → phase "started", node_done → "completed",
        //   plan pick → step{node:"pick_plan", detailJson},
        //   exec done → step{node:"execute", detailJson}.
        // We reconstruct the active-node highlight from those. The successor map
        // mirrors agents/golem/src/agent/graph_v2.py's happy-path edges, so the
        // UI advances ahead of the long phases instead of showing "bootstrap"
        // throughout extract_entities. Terminal nodes drop currentNode to null.
        onStep: (step) => {
          const node = step.node
          if (node === 'pick_plan' && step.detailJson) {
            // Annotate the current node with the picked plan — don't clobber it,
            // or the "Thinking... (execute)" line is wiped the instant it shows.
            try {
              const d = JSON.parse(step.detailJson) as {
                source?: string
                patternId?: string
                score?: number
              }
              const planNote = d.patternId
                ? ` — ${d.source ?? 'plan'} ${d.patternId} @ ${typeof d.score === 'number' ? d.score.toFixed(2) : ''}`
                : ''
              if (currentNode.value) {
                agentStatus.value = `Thinking... (${currentNode.value})${planNote}`
              }
            } catch {
              /* malformed detailJson — skip the annotation */
            }
            return
          }
          if (node === 'execute' && step.detailJson) {
            /* row_count + duration_ms surfaced via the envelope already */
            return
          }
          if (step.phase === 'completed') {
            const next = NEXT_NODE_HAPPY_PATH[node] ?? null
            if (next) {
              currentNode.value = next
              agentStatus.value = `Thinking... (${next})`
              if (traversedNodes.value[traversedNodes.value.length - 1] !== next) {
                traversedNodes.value.push(next)
              }
            }
            return
          }
          currentNode.value = node
          currentTool.value = null
          agentStatus.value = `Thinking... (${node})`
          if (traversedNodes.value[traversedNodes.value.length - 1] !== node) {
            traversedNodes.value.push(node)
          }
        },
        onEnvelope: (envelope) => {
          store.appendOrReplaceEnvelope(envelope)
          const chipStore = useChipStore()
          if (envelope.chips && envelope.chips.length > 0) {
            chipStore.setDynamicChips(promptChipsOf(envelope.chips))
          }
        },
        onError: ({ message }) => {
          store.appendChunkToCurrentAssistant(
            `\n\n_${i18n.global.t('errors.agentFailed')}_ ${message}`,
          )
        },
        onDone: () => {
          /* terminal lifecycle handled below (finalizeAssistantTurn) */
        },
      },
    )
    agentStatus.value = null
    currentNode.value = null
    currentTool.value = null
    store.finalizeAssistantTurn()
  } catch (e: unknown) {
    const errMsg = i18n.global.t('errors.agentFailed')
    const detail = e instanceof Error ? e.message : String(e)
    store.appendChunkToCurrentAssistant(`\n\n_${errMsg}_ ${detail}`)
    store.finalizeAssistantTurn()
    agentStatus.value = null
  }
}

/** `/new` slash command — start a new session. POST /v1/session mints a fresh
 * server session; we adopt its id, clear the chat, and re-seed discovery. */
const startNewSession = async () => {
  const store = ensureChatStore()
  store.clear()
  traversedNodes.value = []
  currentNode.value = null
  currentTool.value = null
  try {
    const session = await irisStream.createSession()
    sessionId.value = session.sessionId
    const chipStore = useChipStore()
    chipStore.setStaticChips(session.staticChips.map(toSuggestedChip))
    exampleQuestions.value = session.exampleQuestions
    packageNames.value = session.packages
    agentVersion.value = session.agentVersion
  } catch (err) {
    console.warn('[useAgentSession] startNewSession failed', err)
    sessionId.value = keyGen()
  }
  setWelcomeBubble(selectedLang.value)
}

// ----- Stage 2.3: edit-and-resend via the typed-action channel -----

/** Edit-and-resend (PD edit_resend): discard the turns after the edited user
 * bubble's server turn, then re-run the edited question through POST /v1/action.
 * Owns the optimistic discard + streaming lifecycle so ChatBubble stays a view.
 * Falls back to a plain turn when the bubble has no server turnId yet (a turn
 * that never finalized). Returns nothing; throws so the caller can toast. */
const editAndResend = async (fromMessageId: string, editedText: string) => {
  const store = ensureChatStore()
  if (store.streaming) return
  const target = store.messages.find((m) => m.id === fromMessageId)
  const fromTurnId = target?.turnId

  store.discardAfter(fromMessageId)
  const assistantMsg = store.beginAssistantTurn()

  const handlers = {
    onTurnId: (turnId: string) => {
      if (target) target.turnId = turnId
      assistantMsg.turnId = turnId
    },
    onEnvelope: (envelope: FormatEnvelope) => {
      store.appendOrReplaceEnvelope(envelope)
      const chipStore = useChipStore()
      if (envelope.chips && envelope.chips.length > 0) {
        chipStore.setDynamicChips(promptChipsOf(envelope.chips))
      }
    },
    onError: ({ message }: { message: string }) => {
      store.appendChunkToCurrentAssistant(`\n\n_${i18n.global.t('errors.agentFailed')}_ ${message}`)
    },
  }

  try {
    if (fromTurnId) {
      await irisStream.editResend(
        { sessionId: sessionId.value, fromTurnId, editedQuestion: editedText },
        handlers,
      )
    } else {
      // No server turn id (never finalized) — degrade to a plain re-issue.
      const env = await irisStream.turn({ sessionId: sessionId.value, question: editedText })
      if (env) handlers.onEnvelope(env)
    }
    store.removeDiscarded(fromMessageId)
    store.clearPendingDiscard(fromMessageId)
    store.finalizeAssistantTurn()
    void loadSessions()
  } catch (e) {
    store.restoreDiscarded(fromMessageId)
    store.finalizeAssistantTurn()
    throw e
  }
}

// ----- Stage 3.2: routing re-issues (RoutingPickChip / chip_invocation /
// reask_agent / investigate) -----

/** Shared streaming envelope for a re-issue: optionally show a user bubble,
 * begin an assistant turn, stream through `start`, and finalize. Mirrors
 * `sendMessage`'s envelope/chips handling; throws so the caller can toast. */
const runReissue = async (opts: {
  userText?: string
  start: (handlers: StreamHandlers) => Promise<void>
}) => {
  const store = ensureChatStore()
  if (store.streaming) return
  if (opts.userText) store.addUserMessage(opts.userText)
  const assistantMsg = store.beginAssistantTurn()
  const handlers: StreamHandlers = {
    onTurnId: (turnId) => {
      assistantMsg.turnId = turnId
    },
    onEnvelope: (envelope) => {
      store.appendOrReplaceEnvelope(envelope)
      if (envelope.chips && envelope.chips.length > 0) {
        useChipStore().setDynamicChips(promptChipsOf(envelope.chips))
      }
    },
    onError: ({ message }) => {
      store.appendChunkToCurrentAssistant(`\n\n_${i18n.global.t('errors.agentFailed')}_ ${message}`)
    },
  }
  try {
    await opts.start(handlers)
    store.finalizeAssistantTurn()
    void loadSessions()
  } catch (e) {
    store.finalizeAssistantTurn()
    throw e
  }
}

/** RoutingPickChip click — re-issue the original question pinned to `agentId`
 * (routing_hint, Layer-0 through Themis). The question is already on screen, so
 * no new user bubble is added. */
const pickRoutingAgent = (agentId: string, question: string) =>
  runReissue({
    start: (h) =>
      irisStream.streamTurn({ sessionId: sessionId.value, question, routingHintAgentId: agentId }, h),
  })

/** Prompt-chip click — re-submit the chip as a normal turn (chip_invocation,
 * through Themis). The chip's prompt becomes the user's question bubble. */
const submitChip = (prompt: string, patternId?: string) =>
  runReissue({
    userText: prompt,
    start: (h) => typedAction.chipInvocation({ sessionId: sessionId.value, prompt, patternId }, h),
  })

/** Re-ask (PD-14) — re-route `turnId` pinned to `targetAgentId`; the BFF records
 * the corrected_agent_id misroute label. */
const reaskAgent = (turnId: string, targetAgentId: string) =>
  runReissue({
    start: (h) => typedAction.reaskAgent({ sessionId: sessionId.value, turnId, targetAgentId }, h),
  })

/** Investigate (PD-1) — escalate `turnId` to Pythia with a proposed question. */
const investigateTurn = (turnId: string, proposedQuestion?: string) =>
  runReissue({
    start: (h) =>
      typedAction.investigate({ sessionId: sessionId.value, turnId, proposedQuestion }, h),
  })

// ----- Stage 3.2: table data-shaping typed actions (sort/filter/paginate) -----

/** Dispatch a data-shaping typed action: the BFF reshapes the bubble's cached
 * rows and streams back a **replacing** envelope (same bubble_id). No new bubble,
 * no thinking indicator — the existing bubble updates in place. Throws so the
 * caller can toast. */
const dispatchShaping = async (run: (handlers: StreamHandlers) => Promise<void>) => {
  const store = ensureChatStore()
  if (store.streaming) return
  const handlers: StreamHandlers = {
    onEnvelope: (envelope) => {
      store.appendOrReplaceEnvelope(envelope, { attachToStreaming: false })
      if (envelope.chips && envelope.chips.length > 0) {
        useChipStore().setDynamicChips(promptChipsOf(envelope.chips))
      }
    },
    onError: ({ message }) => {
      console.warn('[useAgentSession] shaping failed', message)
    },
  }
  await run(handlers)
}

const sortTable = (bubbleId: string, column: string, direction: 'asc' | 'desc') =>
  dispatchShaping((h) => typedAction.sort({ sessionId: sessionId.value, bubbleId, column, direction }, h))

const filterTable = (
  bubbleId: string,
  column: string,
  operator: FilterOperator,
  value: unknown,
) =>
  dispatchShaping((h) =>
    typedAction.filter({ sessionId: sessionId.value, bubbleId, column, operator, value }, h),
  )

const paginateTable = (bubbleId: string, page: number, pageSize: number) =>
  dispatchShaping((h) =>
    typedAction.paginate({ sessionId: sessionId.value, bubbleId, page, pageSize }, h),
  )

/** Row drilldown (select_row) — opens the drilled view as a NEW bubble. */
const drillRow = (bubbleId: string, rowIndex: number) =>
  runReissue({
    start: (h) => typedAction.selectRow({ sessionId: sessionId.value, bubbleId, rowIndex }, h),
  })

// ----- Stage 2.3: multi-session surface (list / switch / hydrate / reset+undo) -----

// The caller's session summaries (left rail). Refreshed on demand.
const sessions = ref<SessionSummaryDto[]>([])
// True right after a `/reset` (or edit_resend), while the latest snapshot is
// still restorable via POST /v1/session/{id}/undo. Cleared once undone.
const lastResetUndoable = ref(false)

/** GET /v1/sessions — refresh the rail's session list. Best-effort. */
const loadSessions = async () => {
  try {
    sessions.value = await irisStream.listSessions()
  } catch (err) {
    console.warn('[useAgentSession] loadSessions failed', err)
  }
}

/** Rebuild the conversation from the server: GET /v1/session/{id} for the turn
 * pointers, then each visible turn's stored envelope in parallel. Maps each
 * turn to a user-question bubble + an assistant-envelope bubble, both tagged
 * with the server `turnId` (so edit_resend can name `fromTurnId`). */
const hydrateSession = async (id: string) => {
  const store = ensureChatStore()
  const session = await irisStream.getSession(id)
  const envelopes = await Promise.all(
    session.turns.map((t) =>
      irisStream.getSessionTurn(id, t.turnId).catch((err) => {
        console.warn('[useAgentSession] getSessionTurn failed', t.turnId, err)
        return null
      }),
    ),
  )
  const msgs: ChatMessage[] = []
  session.turns.forEach((t, i) => {
    const ts = new Date(t.createdAt)
    if (t.question) {
      msgs.push({ id: keyGen(), role: 'user', content: t.question, timestamp: ts, turnId: t.turnId })
    }
    const envJson = envelopes[i]?.envelope
    if (envJson) {
      const env = FormatEnvelope.fromJSON(envJson)
      msgs.push({
        id: keyGen(),
        role: 'assistant',
        content: env.text ?? '',
        timestamp: ts,
        envelope: env,
        turnId: t.turnId,
      })
    }
  })
  store.setMessages(msgs)
  if (msgs.length === 0) setWelcomeBubble(selectedLang.value)
}

/** Switch the active session (left-rail click): adopt the id and hydrate. */
const switchSession = async (id: string) => {
  if (id === sessionId.value) return
  const store = ensureChatStore()
  if (store.streaming) return
  sessionId.value = id
  traversedNodes.value = []
  currentNode.value = null
  currentTool.value = null
  lastResetUndoable.value = false
  await hydrateSession(id)
}

/** `/reset` slash command — snapshot + clear server turns, clear the local
 * conversation, and arm the undo affordance. */
const resetCurrentSession = async () => {
  const store = ensureChatStore()
  await irisStream.resetSession(sessionId.value)
  store.clear()
  lastResetUndoable.value = true
  setWelcomeBubble(selectedLang.value)
  void loadSessions()
}

/** Undo the latest reset/edit_resend by restoring the BFF snapshot, then
 * re-hydrate the (now restored) conversation. */
const undoLastReset = async () => {
  await irisStream.undoSession(sessionId.value)
  lastResetUndoable.value = false
  await hydrateSession(sessionId.value)
  void loadSessions()
}

export function useAgentSession() {
  const store = ensureChatStore()
  return {
    // identity
    agentKey,
    sessionId,
    selectedLang,
    customTitle,
    languages,
    defaultTranslations,
    // chat state — proxied to chatStore
    chatStore: store,
    prompt,
    agentStatus,
    // graph state
    currentNode,
    currentTool,
    traversedNodes,
    // Phase 7 pre-send flags
    desiredFormat,
    dryRunNext,
    // Row-detail selection
    armedSelection,
    armSelection,
    clearSelection,
    // Stage 07-B session bootstrap surface
    exampleQuestions,
    packageNames,
    agentVersion,
    // Stage 2.3 multi-session surface
    sessions,
    lastResetUndoable,
    // actions
    init,
    changeLanguage,
    handleOptionClick,
    sendMessage,
    startNewSession,
    loadSessions,
    hydrateSession,
    switchSession,
    editAndResend,
    resetCurrentSession,
    undoLastReset,
    // Stage 3.2 routing re-issues
    pickRoutingAgent,
    submitChip,
    reaskAgent,
    investigateTurn,
    // Stage 3.2 table data-shaping
    sortTable,
    filterTable,
    paginateTable,
    drillRow,
  }
}
