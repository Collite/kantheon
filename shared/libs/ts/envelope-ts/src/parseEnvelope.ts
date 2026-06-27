// Defensive parser for incoming FormatEnvelope v2 JSON (SSE / REST).
//
// Ported from ai-platform/frontends/agents-fe/src/types/envelope.ts
// (Stage 07-B). Validates that the raw wire object carries every required v2
// field BEFORE it is handed to `normalizeEnvelope` + the generated
// `FormatEnvelope.fromJSON`, so contract drift surfaces as a thrown error at
// the edge instead of a silently-empty bubble downstream.
//
// Operates on the RAW v2 shape (snake_case keys), not the generated camelCase
// type — it is the front door, run before normalisation.

/** The raw v2 wire shape, kept opaque — renderers narrow locally after normalise. */
export type V2EnvelopeRaw = Record<string, unknown>

const REQUIRED_V2_FIELDS = [
  'bubble_id',
  'turn_id',
  'thread_id',
  'format',
  'plan_source',
  'plan_score',
  'created_at',
  'agent_version',
] as const

/**
 * Throws when the input is not an object or lacks a required v2 field, so call
 * sites surface contract drift instead of rendering nothing. Returns the raw
 * (un-normalised) envelope on success.
 */
export function parseEnvelope(raw: unknown): V2EnvelopeRaw {
  if (!raw || typeof raw !== 'object') {
    throw new Error('parseEnvelope: not an object')
  }
  const o = raw as Record<string, unknown>
  for (const k of REQUIRED_V2_FIELDS) {
    if (!(k in o)) {
      throw new Error(`parseEnvelope: missing required field '${k}'`)
    }
  }
  return o
}
