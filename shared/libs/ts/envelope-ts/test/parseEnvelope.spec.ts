import { parseEnvelope } from '../src/index'

const full = {
  bubble_id: 'b',
  turn_id: 't',
  thread_id: 's',
  format: { kind: 'plaintext' },
  plan_source: 'pattern',
  plan_score: 0.5,
  created_at: '2026-06-17T00:00:00Z',
  agent_version: 'golem-v2@1.4.0',
}

describe('parseEnvelope', () => {
  it('returns the validated envelope faithfully when all required fields are present', () => {
    const parsed = parseEnvelope(full)
    // Faithful pass-through of the actual field values (not just object identity).
    expect(parsed.bubble_id).toBe('b')
    expect(parsed.plan_source).toBe('pattern')
    expect((parsed.format as Record<string, unknown>).kind).toBe('plaintext')
  })

  it('returns a structurally-valid but distinct envelope unchanged', () => {
    const other = {
      ...full,
      bubble_id: 'b-9',
      plan_source: 'amend',
      extra: { nested: true },
    }
    const parsed = parseEnvelope(other)
    expect(parsed.bubble_id).toBe('b-9')
    expect(parsed.plan_source).toBe('amend')
    expect(parsed.extra).toEqual({ nested: true })
  })

  it('throws on non-objects', () => {
    expect(() => parseEnvelope(null)).toThrow('not an object')
    expect(() => parseEnvelope('x')).toThrow('not an object')
    expect(() => parseEnvelope(42)).toThrow('not an object')
  })

  it.each([
    'bubble_id',
    'turn_id',
    'thread_id',
    'format',
    'plan_source',
    'plan_score',
    'created_at',
    'agent_version',
  ])('throws when required field %s is missing', (field) => {
    const partial = { ...full } as Record<string, unknown>
    delete partial[field]
    expect(() => parseEnvelope(partial)).toThrow(`missing required field '${field}'`)
  })
})
