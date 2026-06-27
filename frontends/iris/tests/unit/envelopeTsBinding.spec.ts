import { describe, it, expect } from 'vitest'
import { FormatEnvelope, Block, decodeV2Envelope } from '@kantheon/envelope-ts'

// Iris Phase 2 Stage 2.2 T0 — proves the @kantheon/envelope-ts binding resolves
// in the FE build (vite/vitest alias) and the generated envelope/v1 messages +
// edge helpers load. The renderer migration off the v2 src/types/envelope.ts
// follows in T1.
describe('@kantheon/envelope-ts binding', () => {
  it('exposes the envelope/v1 message bindings + edge helpers', () => {
    expect(typeof FormatEnvelope.fromJSON).toBe('function')
    expect(typeof Block.fromJSON).toBe('function')
    expect(typeof decodeV2Envelope).toBe('function')
  })

  it('FormatEnvelope.fromJSON returns a typed object', () => {
    const env = FormatEnvelope.fromJSON({})
    expect(env).toBeTypeOf('object')
  })
})
