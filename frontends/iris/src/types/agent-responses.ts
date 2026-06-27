// Iris FE wire types for the iris-bff REST/SSE surface (Phase 2 Stage 2.2 re-point).
//
// The FE now talks ONLY to the iris-bff (contracts §2). These mirror the BFF's
// kotlinx DTOs verbatim — camelCase property names. The streamed chat payloads
// are the generated iris/v1 IrisStreamEvent arms (StepEvent / ErrorEvent /
// DoneEvent + envelope/v1 FormatEnvelope) from @kantheon/envelope-ts and are NOT
// redeclared here — the service imports them from `@/types/envelope`.
import type { SuggestedChip } from './chips'

// ----- /ready (BFF readiness probe; not under /v1) -----
export interface ReadyResponse {
  status: string // "UP" | "NOT_READY"
}

// ----- /v1/session, /v1/session/{id}, /v1/session/{id}/reset -----
export interface SessionChipDto {
  display: string
  prompt: string
  source: string
}

export interface TurnPointerDto {
  turnId: string
  agentId: string
  question?: string | null
  artifactRef?: string | null
  displayedBlockIds: string[]
  status: string
  origin: string
  createdAt: string
}

export interface SessionDto {
  sessionId: string
  userId: string
  tenantId: string
  entityContext?: unknown | null
  turns: TurnPointerDto[]
  createdAt: string
  updatedAt: string
  // Discovery surface (BFF-grow): mirrored from golem /v2/session on POST
  // /v1/session. Empty defaults on GET/reset (the BFF only enriches on create).
  staticChips: SessionChipDto[]
  exampleQuestions: string[]
  packages: string[]
  agentVersion: string
}

// ----- /v1/sessions -----
export interface SessionSummaryDto {
  sessionId: string
  title: string
  turnCount: number
  updatedAt: string
}

// ----- /v1/session/{id}/turn/{turnId} (history hydration) -----
export interface TurnEnvelopeDto {
  turnId: string
  agentId: string
  envelope?: unknown | null
  status: string
}

// ----- /v1/action (typed actions; Stage 2.3 lands edit_resend only) -----
export interface TypedActionDto {
  kind: string
  /** Rule-7 args: a JSON string, schema per kind (contracts §2.4). */
  payloadJson: string
}
export interface TypedActionRequestDto {
  sessionId: string
  bubbleId?: string
  action: TypedActionDto
}

// ----- /v1/chat/stream, /v1/chat/turn -----
export interface ChatTurnRequestDto {
  sessionId: string
  question: string
  desiredFormat?: string
  // Phase 3 routing: pin this turn to a chosen agent (Layer-0 routing hint,
  // through Themis). Set when a RoutingPickChip is clicked — the BFF skips the
  // four-layer cascade and dispatches straight to `routingHintAgentId`.
  routingHintAgentId?: string
}

// ----- /v1/chat/resume -----
export interface ChatResumeRequestDto {
  sessionId: string
  resumeToken: string
  selectedOptionId?: string
  freeTextAnswer?: string
}

// ----- /v1/refresh -----
export interface RefreshResultDto {
  service: string
  status: string
  detail?: string | null
  version?: string | null
}
export interface RefreshResponseDto {
  results: RefreshResultDto[]
}

// ----- FE-local: armed row-detail selection -----
// envelope/v1 typed actions (sort / filter / select_row) ride POST /v1/action,
// which is Iris Phase 3 (contracts §2.2/§2.4). Until then this is FE-only UI
// state (the "Show detail" chip in ChatInput); it is deliberately NOT placed on
// the v1 turn request, which carries no `selection` field.
export interface TurnSelection {
  bubble_id: string
  row_indices: number[]
}

/** Map a BFF SessionChipDto to the FE-local SuggestedChip view-model. */
export function toSuggestedChip(c: SessionChipDto): SuggestedChip {
  return { display: c.display, prompt: c.prompt, source: c.source || 'static' }
}
