// v2 → envelope/v1 normalisation shim (Iris Stage 1.1).
//
// Bridges the proven FormatEnvelope v2 wire (agents-fe Stage 07-B) to the
// generated envelope/v1 `FormatEnvelope.fromJSON`. Two classes of mismatch the
// shim resolves (contracts.md §1.1 "v2 → v1 lossless-mapping notes", §5 adapter):
//
//   1. Enum casing — v2 emits lowercase enum strings ("pattern", "table");
//      proto3 JSON (and ts-proto's *FromJSON) expect the proto enum NAME
//      ("PATTERN", "TABLE"). Unmapped → UNRECOGNIZED. The shim uppercases.
//   2. Opaque-JSON fields — v2 carries `content`, chart `vega_lite_spec`, table
//      filter `value`, `current_view.args`, chip `prefilled_args` as nested
//      JSON; envelope/v1 carries them as Rule-7 `*_json` strings. The shim
//      stringifies.
//
// `provenance` (PD-9) and `InvestigateChip` (PD-1) are ADDITIVE — absent in v2
// samples; the shim never invents them. The same transform is reused BFF-side
// (GolemV2Client, Iris Stage 1.3) and FE-side.

import { FormatEnvelope } from './generated/org/tatrman/kantheon/envelope/v1/envelope'

type Json = Record<string, unknown>

const isObject = (v: unknown): v is Json => !!v && typeof v === 'object' && !Array.isArray(v)
// Rule-7: *_json fields carry a JSON-encoded value, so a consumer can
// JSON.parse it back. Always encode — including strings ("Mléko" → "\"Mléko\"")
// — never pass a raw string through (that would not round-trip through parse).
// Only `undefined` means "absent" (field dropped); an explicit JSON `null` IS a
// value and encodes to the string "null" (else a downstream JSON.parse("") throws).
const toJsonString = (v: unknown): string | undefined =>
  v === undefined ? undefined : JSON.stringify(v)

/** Map a v2 lowercase enum string to its proto enum NAME (UPPERCASE). */
const enumName = (v: unknown): unknown => (typeof v === 'string' ? v.toUpperCase() : v)

function normalizeFormat(format: unknown): Json | undefined {
  if (!isObject(format)) return undefined
  const out: Json = { kind: enumName(format.kind) }

  // v2 flattens per-kind details onto `format` (format.table / format.chart /
  // format.markdown); some older shapes nest under `format.details`. Accept both.
  const details = isObject(format.details) ? format.details : undefined
  const table = format.table ?? (format.kind === 'table' ? details : undefined)
  const chart = format.chart ?? (format.kind === 'chart' ? details : undefined)
  const markdown = format.markdown ?? (format.kind === 'markdown' ? details : undefined)

  if (isObject(table)) out.table = normalizeTable(table)
  if (isObject(chart)) out.chart = normalizeChart(chart)
  if (isObject(markdown)) out.markdown = markdown
  return out
}

function normalizeTable(t: Json): Json {
  const out: Json = { ...t }
  // v2 TS interface uses camelCase `alternateColors`; proto field is `alternate_colors`.
  if ('alternateColors' in out) {
    out.alternate_colors = out.alternateColors
    delete out.alternateColors
  }
  if (Array.isArray(out.filters)) {
    out.filters = out.filters.map((f) =>
      isObject(f) && 'value' in f
        ? { ...f, value_json: toJsonString((f as Json).value), value: undefined }
        : f,
    )
  }
  return out
}

function normalizeChart(c: Json): Json {
  const out: Json = { ...c }
  if ('vega_lite_spec' in out) {
    out.vega_lite_spec_json = toJsonString(out.vega_lite_spec)
    delete out.vega_lite_spec
  }
  if ('rows' in out) {
    out.rows_json = toJsonString(out.rows)
    delete out.rows
  }
  return out
}

function normalizeChip(chip: unknown): Json | undefined {
  if (!isObject(chip)) return undefined
  // v2 chip == envelope/v1 PromptChip; wrap into the `Chip.prompt` oneof arm.
  const prompt: Json = {
    display: chip.display,
    prompt: chip.prompt,
    source: chip.source,
  }
  if (chip.pattern_id !== undefined) prompt.pattern_id = chip.pattern_id
  if (chip.prefilled_args !== undefined) prompt.prefilled_args_json = toJsonString(chip.prefilled_args)
  return { prompt }
}

function normalizeCurrentView(cv: unknown): Json | undefined {
  if (!isObject(cv)) return undefined
  const out: Json = { ...cv }
  if ('args' in out) {
    out.args_json = toJsonString(out.args)
    delete out.args
  }
  return out
}

/**
 * Normalise a raw v2 envelope object into a shape `FormatEnvelope.fromJSON`
 * accepts cleanly. Pure — does not mutate the input.
 */
export function normalizeEnvelopeJson(raw: Record<string, unknown>): Record<string, unknown> {
  const out: Json = { ...raw }

  if ('plan_source' in out) out.plan_source = enumName(out.plan_source)
  if ('content' in out) {
    out.content_json = toJsonString(out.content)
    delete out.content
  }
  if ('format' in out) out.format = normalizeFormat(out.format)
  if (Array.isArray(out.chips)) out.chips = out.chips.map(normalizeChip).filter(Boolean)
  if ('current_view' in out) out.current_view = normalizeCurrentView(out.current_view)

  return out
}

/** Convenience: normalise + decode in one step to the typed envelope/v1 message. */
export function decodeV2Envelope(raw: Record<string, unknown>): FormatEnvelope {
  return FormatEnvelope.fromJSON(normalizeEnvelopeJson(raw))
}
