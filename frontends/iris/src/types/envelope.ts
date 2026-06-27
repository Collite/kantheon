// Iris FE wire types (Phase 2 Stage 2.2). The generated envelope/v1 + iris/v1
// bindings in @kantheon/envelope-ts are the single source of truth — this module
// re-exports them and adds only FE-local presentation helpers. The hand-rolled
// v2 FormatEnvelope that lived here (agents-fe Stage 07-B) is retired; incoming
// v2 payloads are decoded to envelope/v1 at the ingest edge via decodeV2Envelope.
export * from '@kantheon/envelope-ts'

import { type FormatEnvelope } from '@kantheon/envelope-ts'

// Per-bubble client-side display overlay (NOT part of the wire contract):
// column-visibility overrides, a `viewKind` override (Show-as-Chart), etc.
export interface DisplayState {
  [key: string]: unknown
}

// envelope/v1 carries the row/payload as a Rule-7 JSON string (`contentJson`);
// renderers want the parsed value. Parse in one place; undefined on absent/bad.
export function envelopeContent(env: Pick<FormatEnvelope, 'contentJson'> | undefined): unknown {
  return parseJsonField(env?.contentJson)
}

// Parse a Rule-7 `*_json` string field (chart rowsJson / vegaLiteSpecJson, table
// filter valueJson, current_view argsJson, …). Empty/absent/malformed → undefined.
export function parseJsonField(json: string | undefined): unknown {
  if (json === undefined || json === '') return undefined
  try {
    return JSON.parse(json)
  } catch {
    return undefined
  }
}
