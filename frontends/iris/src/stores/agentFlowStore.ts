// Phase 2 — Agent Flow store.
//
// Accumulates a textual transcript of LangGraph events that fly past on
// `/chat/stream`: which logic node is active, which MCP tool was invoked,
// which envelope was emitted. Lives entirely in memory; persists across
// chat turns within a session but is wiped on reload.
//
// Renderer: `frontend/src/components/panes/AgentFlowPane.vue`.
// Producer: `frontend/src/services/agentService.ts` calls `append(...)`
// from the existing `step` / `tool_call` / `envelope` SSE handlers — no
// new BE work.
//
// Capacity: FIFO-capped at `MAX_ENTRIES` to keep long debug sessions from
// growing unbounded. Default 500; override via `VITE_AGENT_FLOW_MAX_ENTRIES`.
import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface FlowEntryStep {
  ts: string // 'HH:MM:SS'
  kind: 'step'
  node: string
}
export interface FlowEntryTool {
  ts: string
  kind: 'tool'
  toolName: string
  server: string
  /** Args payload from the BE — verbatim. The renderer decides whether to
   *  surface it (Verbose toggle). May be undefined if no args were
   *  attached (current Phase-2 BE doesn't send any). */
  args?: unknown
}
export interface FlowEntryEnvelope {
  ts: string
  kind: 'envelope'
  bubbleId: string
  envelopeKind: string
  /** Optional details passed verbatim. Same Verbose-gating as `args`. */
  details?: unknown
}
export type FlowEntry = FlowEntryStep | FlowEntryTool | FlowEntryEnvelope

const DEFAULT_MAX_ENTRIES = 500

const parseMaxEntries = (): number => {
  const raw = import.meta.env.VITE_AGENT_FLOW_MAX_ENTRIES as string | undefined
  if (!raw) return DEFAULT_MAX_ENTRIES
  const n = Number.parseInt(raw, 10)
  return Number.isFinite(n) && n > 0 ? n : DEFAULT_MAX_ENTRIES
}

const formatTs = (d: Date = new Date()): string => {
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export const useAgentFlowStore = defineStore('agentFlow', () => {
  const maxEntries = parseMaxEntries()
  const entries = ref<FlowEntry[]>([])
  // Counter incremented on every cap-driven drop, exposed for the
  // `agent_flow.entries_dropped` Phase-2 telemetry expectation. Read-only
  // for the rest of the app.
  const droppedCount = ref(0)

  // Distributive `Omit` over the union preserves discriminated-union
  // narrowing — `append({ kind: 'step', node: '…' })` type-checks against
  // the `FlowEntryStep` arm specifically, not against an over-broad
  // intersection of every variant.
  type FlowEntryInput = FlowEntry extends infer T
    ? T extends { ts: string }
      ? Omit<T, 'ts'> & { ts?: string }
      : never
    : never

  const append = (entry: FlowEntryInput) => {
    const stamped = { ...entry, ts: entry.ts ?? formatTs() } as FlowEntry
    entries.value.push(stamped)
    while (entries.value.length > maxEntries) {
      entries.value.shift()
      droppedCount.value += 1
    }
  }

  const clear = () => {
    entries.value = []
  }

  return {
    entries,
    droppedCount,
    maxEntries,
    append,
    clear,
  }
})
