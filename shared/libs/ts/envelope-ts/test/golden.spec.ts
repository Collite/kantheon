// Golden-sample gate: every recorded FormatEnvelope v2 fixture must survive
// parseEnvelope → normalizeEnvelopeJson → FormatEnvelope.fromJSON, and a
// toJSON round-trip must be stable on the typed subset.
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import {
  FormatEnvelope,
  FormatKind,
  parseEnvelope,
  normalizeEnvelopeJson,
} from '../src/index'

const fixturesDir = join(dirname(fileURLToPath(import.meta.url)), 'fixtures')
const fixtureFiles = readdirSync(fixturesDir).filter((f: string) => f.endsWith('.json'))

const load = (file: string) =>
  JSON.parse(readFileSync(join(fixturesDir, file), 'utf-8')) as Record<string, unknown>

describe('golden-sample v2 → envelope/v1', () => {
  it('has the full corpus (≥10 fixtures)', () => {
    expect(fixtureFiles.length).toBeGreaterThanOrEqual(10)
  })

  it.each(fixtureFiles)('%s parses without throwing and preserves required fields', (file: string) => {
    const raw = parseEnvelope(load(file))
    const env = FormatEnvelope.fromJSON(normalizeEnvelopeJson(raw))

    // Required v2 identity fields survive the round-trip.
    expect(env.bubbleId).toBe(raw.bubble_id)
    expect(env.turnId).toBe(raw.turn_id)
    expect(env.threadId).toBe(raw.thread_id)
    expect(env.createdAt).toBe(raw.created_at)
    expect(env.agentVersion).toBe(raw.agent_version)

    // format.kind maps to a known enum (never UNRECOGNIZED) for every fixture.
    expect(env.format?.kind).not.toBe(FormatKind.UNRECOGNIZED)
    expect(env.format?.kind).not.toBeUndefined()

    // plan_source maps to a known PlanSource (no UNRECOGNIZED).
    expect(env.planSource).toBeGreaterThanOrEqual(0)
  })

  it.each(fixtureFiles)('%s is toJSON-stable (decode→encode→decode)', (file: string) => {
    const raw = parseEnvelope(load(file))
    const once = FormatEnvelope.fromJSON(normalizeEnvelopeJson(raw))
    const twice = FormatEnvelope.fromJSON(FormatEnvelope.toJSON(once) as Record<string, unknown>)
    expect(FormatEnvelope.toJSON(twice)).toEqual(FormatEnvelope.toJSON(once))
  })

  it('reads legacy v2 snake_case input AND emits proto-canonical camelCase output', () => {
    // envelope/v1 wire = proto-canonical JSON (camelCase). The binding reads the
    // legacy v2 snake_case (fromJSON has a snake_case fallback) and emits
    // canonical camelCase — the KT side (protobuf JsonFormat) does the same, so
    // the iris-bff ↔ FE envelope/v1 wire is consistent on both ends.
    const env = FormatEnvelope.fromJSON(normalizeEnvelopeJson(parseEnvelope(load('01-table-basic.json'))))
    expect(env.bubbleId).toBe('b-0001') // snake_case `bubble_id` was read
    const json = FormatEnvelope.toJSON(env) as Record<string, unknown>
    expect(json).toHaveProperty('bubbleId') // canonical camelCase out
    expect(json).toHaveProperty('planSource')
    expect(json).not.toHaveProperty('bubble_id')
  })

  it('tolerates the absence of additive fields (provenance / InvestigateChip)', () => {
    // No v2 fixture carries them; chips normalise to the PromptChip arm only.
    const env = FormatEnvelope.fromJSON(normalizeEnvelopeJson(parseEnvelope(load('07-chips.json'))))
    expect(env.chips.length).toBe(4)
    for (const chip of env.chips) {
      expect(chip.prompt).toBeDefined()
      expect(chip.routing).toBeUndefined()
      expect(chip.investigate).toBeUndefined()
    }
  })
})
