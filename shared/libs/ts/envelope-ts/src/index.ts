// @kantheon/envelope-ts — public surface.
//
// Generated bindings for envelope/v1 + iris/v1 (+ the common/v1 types they
// import) plus the hand-written edge helpers. Consumed by frontends/iris
// (Phase 2) — replaces agents-fe's hand-written src/types/envelope.ts.
//
// Generated types are camelCase (idiomatic TS); the envelope/v1 wire is
// proto-canonical JSON (also camelCase) and the binding tolerantly reads the
// legacy v2 snake_case on input (ts-proto snakeToCamel=keys). Use
// `parseEnvelope` → `normalizeEnvelopeJson` → `FormatEnvelope.fromJSON` (or the
// `decodeV2Envelope` convenience) to bring a raw v2 SSE/REST payload in.
//
// `export *` is taken from envelope only (the canonical owner of the ts-proto
// per-file helpers `DeepPartial`/`MessageFns`/`protobufPackage`); the other
// modules re-export their domain symbols explicitly to avoid star-collisions.

// ---- envelope/v1 (canonical * — also carries the shared ts-proto helpers) ----
export * from './generated/org/tatrman/kantheon/envelope/v1/envelope'

// ---- iris/v1 ----
export {
  Session,
  TurnPointer,
  ChatTurnRequest,
  TurnOrigin,
  turnOriginFromJSON,
  turnOriginToJSON,
  ChatResumeRequest,
  TypedActionRequest,
  TypedAction,
  IrisStreamEvent,
  StepEvent,
  ToolCallEvent,
  ThinkingEvent,
  ErrorEvent,
  DoneEvent,
} from './generated/org/tatrman/kantheon/iris/v1/iris'

// ---- common/v1 ----
export {
  AgentId,
  EntityBinding,
  ViewProvenance,
  BlockProvenance,
  HandoffContext,
} from './generated/org/tatrman/kantheon/common/v1/handoff'
export {
  ResponseMessage,
  Severity,
  severityFromJSON,
  severityToJSON,
} from './generated/org/tatrman/kantheon/common/v1/response_message'

// ---- Edge helpers ----
export { parseEnvelope, type V2EnvelopeRaw } from './parseEnvelope'
export { applyFormat } from './formatDirectives'
export { normalizeEnvelopeJson, decodeV2Envelope } from './normalize'
