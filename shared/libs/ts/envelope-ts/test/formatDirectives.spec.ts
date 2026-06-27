import { applyFormat } from '../src/index'

describe('applyFormat', () => {
  it('formats fixed-precision floats', () => {
    expect(applyFormat('%.2f', 1820345.5)).toBe('1820345.50')
    expect(applyFormat('%.0f', 3.7)).toBe('4')
  })

  it('formats integers with zero-padding and width', () => {
    expect(applyFormat('%05d', 42)).toBe('00042')
    expect(applyFormat('%d', -7)).toBe('-7')
    expect(applyFormat('%+d', 7)).toBe('+7')
  })

  it('keeps the sign OUTSIDE the zero-fill (C/printf semantics)', () => {
    // Sign-aware zero-pad: zeros go AFTER the sign, never in front of it.
    expect(applyFormat('%05d', -42)).toBe('-0042')
    expect(applyFormat('%+05d', 42)).toBe('+0042')
    expect(applyFormat('%05d', 42)).toBe('00042') // unchanged — no sign
    expect(applyFormat('%08.2f', -3.5)).toBe('-0003.50') // negative float zero-pad
  })

  it('does not escape %% (faithful to the agents-fe source — the token regex has no %% rule)', () => {
    // The ported interpreter only consumes `%<spec><type>`; a literal `%%` is
    // left untouched. Preserved deliberately (behaviour-preserving port); the
    // FE renderer never relies on %% escaping.
    expect(applyFormat('%.0f%%', 15)).toBe('15%%')
  })

  it('formats exponential and hex', () => {
    expect(applyFormat('%.1e', 12345)).toBe('1.2e+4')
    expect(applyFormat('%E', 12345)).toContain('E')
    expect(applyFormat('%x', 255)).toBe('ff')
  })

  it('falls through to String() for %s and unknown values', () => {
    expect(applyFormat('%s', 'Praha')).toBe('Praha')
    expect(applyFormat('value: %s', 12)).toBe('value: 12')
  })

  it('passes non-finite numbers through as String(value)', () => {
    expect(applyFormat('%.2f', 'n/a')).toBe('n/a')
  })

  it('returns empty string for null/undefined', () => {
    expect(applyFormat('%.2f', null)).toBe('')
    expect(applyFormat('%d', undefined)).toBe('')
  })

  it('left-justifies with the - flag', () => {
    expect(applyFormat('%-5d', 42)).toBe('42   ')
  })
})
