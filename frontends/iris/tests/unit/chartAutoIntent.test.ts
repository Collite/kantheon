import { describe, expect, it } from 'vitest'
import {
  columnStats,
  deriveAutoIntent,
  isNumericColumn,
  projectChartData,
  sumNumericColumn,
  toNumber,
} from '@/components/chat/formats/chartAutoIntent'

describe('toNumber', () => {
  it('parses numbers, numeric strings, and negatives; rejects blanks', () => {
    expect(toNumber(-76000)).toBe(-76000)
    expect(toNumber('-42241.39')).toBe(-42241.39)
    expect(toNumber('  10356.09 ')).toBe(10356.09)
    expect(toNumber('')).toBeNull()
    expect(toNumber('   ')).toBeNull()
    expect(toNumber(null)).toBeNull()
    expect(toNumber(undefined)).toBeNull()
    expect(toNumber('abc')).toBeNull()
    expect(toNumber(NaN)).toBeNull()
  })
})

describe('isNumericColumn', () => {
  const rows = [
    { period: '2026.01', plan: '-76000.00', actual: '-42241.39' },
    { period: '2026.07', plan: '-76000.00', actual: '' }, // blank actual
  ]
  it('treats a column with blanks as numeric when all present cells are numbers', () => {
    expect(isNumericColumn(rows, 'actual')).toBe(true)
    expect(isNumericColumn(rows, 'plan')).toBe(true)
  })
  it('classifies a numeric-string period code as numeric too', () => {
    expect(isNumericColumn(rows, 'period')).toBe(true)
  })
  it('is false for an all-blank column', () => {
    expect(isNumericColumn([{ x: '' }, { x: null }], 'x')).toBe(false)
  })
})

describe('sumNumericColumn', () => {
  it('sums plain numbers', () => {
    expect(sumNumericColumn([{ a: 1 }, { a: 2 }, { a: 3 }], 'a')).toBe(6)
  })
  it('sums numeric strings', () => {
    expect(sumNumericColumn([{ a: '10' }, { a: '2.5' }], 'a')).toBe(12.5)
  })
  it('skips null/blank/non-numeric cells', () => {
    expect(
      sumNumericColumn([{ a: 1 }, { a: null }, { a: '' }, { a: 'x' }, { a: 4 }], 'a'),
    ).toBe(5)
  })
  it('returns 0 for an empty array', () => {
    expect(sumNumericColumn([], 'a')).toBe(0)
  })
  it('returns 0 when the column is absent', () => {
    expect(sumNumericColumn([{ b: 1 }, { b: 2 }], 'a')).toBe(0)
  })
})

describe('columnStats', () => {
  it('computes count/empty/sum/min/max/avg, counting blanks as empty', () => {
    const rows = [{ a: 10 }, { a: 20 }, { a: null }, { a: '' }, { a: '30' }]
    expect(columnStats(rows, 'a')).toEqual({
      rows: 5,
      empty: 2, // null + blank string
      count: 3, // 10, 20, 30
      sum: 60,
      min: 10,
      max: 30,
      avg: 20,
    })
  })
  it('handles negatives and reports null aggregates for an all-blank column', () => {
    expect(columnStats([{ a: -5 }, { a: -1 }], 'a')).toMatchObject({ min: -5, max: -1, sum: -6 })
    expect(columnStats([{ a: '' }, { a: null }], 'a')).toMatchObject({
      count: 0,
      empty: 2,
      sum: 0,
      min: null,
      max: null,
      avg: null,
    })
  })
})

describe('deriveAutoIntent — the reported plán/skutečnost table', () => {
  // Exactly the user's TSV: numeric-string period codes, negative values, and
  // an empty `skutečnost` for the later months.
  const rows = [
    { 'kód_účetního_období': '2026.01', 'plán': '-76000.00', 'skutečnost': '-42241.39' },
    { 'kód_účetního_období': '2026.06', 'plán': '-76000.00', 'skutečnost': '10356.09' },
    { 'kód_účetního_období': '2026.07', 'plán': '-76000.00', 'skutečnost': '' },
    { 'kód_účetního_období': '2026.12', 'plán': '-76000.00', 'skutečnost': '' },
  ]

  it('picks the period column as x and the two numeric columns as series', () => {
    const intent = deriveAutoIntent(rows)
    expect(intent).not.toBeNull()
    // Every column parses as a number, so x falls back to the first column.
    expect(intent!.x).toBe('kód_účetního_období')
    expect(intent!.y).toEqual(['plán', 'skutečnost'])
    expect(intent!.kind).toBe('line')
  })

  it('coerces series cells to numbers and blanks to null gaps; keeps x labels verbatim', () => {
    const intent = deriveAutoIntent(rows)!
    const data = projectChartData(rows, intent)
    expect(data[0]).toEqual({
      'kód_účetního_období': '2026.01',
      'plán': -76000,
      'skutečnost': -42241.39,
    })
    // Blank skutečnost → null (a gap), not NaN/0.
    expect(data[2]?.['skutečnost']).toBeNull()
    expect(data[2]?.['plán']).toBe(-76000)
  })

  it('prefers a real non-numeric label column for x when present', () => {
    const named = [
      { month: 'Leden', plan: 10, actual: 8 },
      { month: 'Únor', plan: 10, actual: 9 },
    ]
    const intent = deriveAutoIntent(named)!
    expect(intent.x).toBe('month')
    expect(intent.y).toEqual(['plan', 'actual'])
  })

  it('returns null when there is nothing chartable', () => {
    expect(deriveAutoIntent([])).toBeNull()
    expect(deriveAutoIntent([{ a: 'x', b: 'y' }])).toBeNull() // no numeric series
  })
})
