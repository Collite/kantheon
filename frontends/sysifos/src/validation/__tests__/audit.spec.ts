import { describe, it, expect } from 'vitest'
import { prettyJson, diffKeys } from '@/validation/audit'

describe('prettyJson', () => {
  it('pretty-prints an object and a JSON string alike', () => {
    expect(prettyJson({ a: 1 })).toContain('"a": 1')
    expect(prettyJson('{"a":1}')).toContain('"a": 1')
  })
  it('renders an em dash for null/undefined', () => {
    expect(prettyJson(null)).toBe('—')
    expect(prettyJson(undefined)).toBe('—')
  })
})

describe('diffKeys', () => {
  it('returns the keys whose value changed (incl. added/removed)', () => {
    const changed = diffKeys({ name: 'A', email: 'x@y' }, { name: 'B', phone: '1' })
    expect([...changed].sort()).toEqual(['email', 'name', 'phone'])
  })
  it('is empty when nothing changed', () => {
    expect(diffKeys({ a: 1 }, { a: 1 }).size).toBe(0)
  })
  it('treats a null before (insert) as all-after changed', () => {
    expect([...diffKeys(null, { a: 1, b: 2 })].sort()).toEqual(['a', 'b'])
  })
})
