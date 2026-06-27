// new-Golem Stage 07-B — typed-action channel for v2 turns.
//
// Per OQ-07.B drilldowns are prefill-only on the FE; no DrillInvocation type
// crosses the wire. ChipInvocation widens to carry the v2 source + pattern_id
// + prefilled_args so the back-end can route a pattern_derived chip directly.

export interface RowSelection {
  kind: 'select_row'
  row_number: number
  original_message_id: string
}

export interface ChipInvocation {
  kind: 'chip_click'
  /** v2 chip source (see envelope.ChipSource). */
  chip_source: 'static' | 'heuristic' | 'pattern_derived' | 'llm_topup'
  /** Present for `pattern_derived` chips so the back-end can shortcut to the pattern. */
  pattern_id?: string
  /** Present for `pattern_derived` chips so the cascade can skip re-parsing args. */
  prefilled_args?: Record<string, unknown>
  /** Always present — the human-readable text dispatched as the next turn's user_text. */
  user_facing_text: string
}

export interface EditResend {
  kind: 'edit_resend'
  edit_of_message_id: string
  new_text: string
}

export type TypedAction = RowSelection | ChipInvocation | EditResend

export interface ActionRequest {
  session_id: string
  action: TypedAction
  correlation_id?: string
}
