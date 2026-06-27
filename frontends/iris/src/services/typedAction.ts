// Typed-action client (Iris Phase 3 — POST /v1/action).
//
// envelope/v1 typed actions re-issue against the producing turn WITHOUT a fresh
// LLM parse. The data-shaping kinds (`sort`/`filter`/`paginate`) reshape the
// bubble's cached rows BFF-side and stream a **replacing** envelope (same
// bubble_id); `select_row` opens a drilldown as a **new** bubble;
// `chip_invocation` re-submits a chip as a normal turn; `reask_agent` (PD-14)
// re-routes pinned to a chosen agent and records the misroute label;
// `investigate` (PD-1) escalates a turn to Pythia. Every kind streams over the
// same `IrisStreamEvent` SSE consumer as a chat turn — callers pass the usual
// `StreamHandlers` (`onEnvelope`/`onError`/`onDone`/…).
//
// The wire shape is `{sessionId, bubbleId?, action:{kind, payloadJson}}` where
// `payloadJson` is a Rule-7 JSON string with the per-kind schema (contracts
// §2.4). These builders own the per-kind payload; `irisStream.action` owns the
// transport.
import { irisStream, type StreamHandlers } from '@/services/irisStream'

/** TableFilterSpec operators (envelope/v1 §1.1). */
export type FilterOperator = 'eq' | 'neq' | 'lt' | 'lte' | 'gt' | 'gte' | 'contains' | 'in'

const dispatch = (
  sessionId: string,
  bubbleId: string | undefined,
  kind: string,
  payload: Record<string, unknown>,
  handlers: StreamHandlers,
): Promise<void> =>
  irisStream.action(
    { sessionId, bubbleId, action: { kind, payloadJson: JSON.stringify(payload) } },
    handlers,
  )

export const typedAction = {
  /** `sort` — reorder the bubble's cached rows by a column (replacing envelope). */
  sort(
    req: { sessionId: string; bubbleId: string; column: string; direction?: 'asc' | 'desc' },
    handlers: StreamHandlers,
  ): Promise<void> {
    return dispatch(req.sessionId, req.bubbleId, 'sort', {
      column: req.column,
      direction: req.direction ?? 'asc',
    }, handlers)
  },

  /** `filter` — subset the cached rows by a column predicate (replacing envelope). */
  filter(
    req: {
      sessionId: string
      bubbleId: string
      column: string
      operator: FilterOperator
      value: unknown
    },
    handlers: StreamHandlers,
  ): Promise<void> {
    return dispatch(req.sessionId, req.bubbleId, 'filter', {
      column: req.column,
      operator: req.operator,
      value: req.value,
    }, handlers)
  },

  /** `paginate` — page the cached rows; a page beyond cache triggers a BFF refetch. */
  paginate(
    req: { sessionId: string; bubbleId: string; page: number; pageSize: number },
    handlers: StreamHandlers,
  ): Promise<void> {
    return dispatch(req.sessionId, req.bubbleId, 'paginate', {
      page: req.page,
      pageSize: req.pageSize,
    }, handlers)
  },

  /** `select_row` — drill into a row; opens a NEW bubble (not a replace). */
  selectRow(
    req: { sessionId: string; bubbleId: string; rowIndex: number },
    handlers: StreamHandlers,
  ): Promise<void> {
    return dispatch(req.sessionId, req.bubbleId, 'select_row', { rowIndex: req.rowIndex }, handlers)
  },

  /** `chip_invocation` — re-submit a chip's prompt as a normal turn (through Themis). */
  chipInvocation(
    req: { sessionId: string; prompt: string; patternId?: string },
    handlers: StreamHandlers,
  ): Promise<void> {
    return dispatch(req.sessionId, undefined, 'chip_invocation', {
      prompt: req.prompt,
      ...(req.patternId ? { patternId: req.patternId } : {}),
    }, handlers)
  },

  /** `reask_agent` (PD-14) — re-route a turn pinned to `targetAgentId`; the BFF
   *  records the corrected_agent_id misroute label. */
  reaskAgent(
    req: { sessionId: string; turnId: string; targetAgentId: string },
    handlers: StreamHandlers,
  ): Promise<void> {
    return dispatch(req.sessionId, undefined, 'reask_agent', {
      turnId: req.turnId,
      targetAgentId: req.targetAgentId,
    }, handlers)
  },

  /** `investigate` (PD-1) — escalate a turn to Pythia with the proposed question. */
  investigate(
    req: { sessionId: string; turnId: string; proposedQuestion?: string },
    handlers: StreamHandlers,
  ): Promise<void> {
    return dispatch(req.sessionId, undefined, 'investigate', {
      turnId: req.turnId,
      ...(req.proposedQuestion ? { proposedQuestion: req.proposedQuestion } : {}),
    }, handlers)
  },
}
