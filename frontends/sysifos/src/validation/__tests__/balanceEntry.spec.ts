import { describe, it, expect } from 'vitest'
import { explainBalanceEntry } from '@/validation/balanceEntry'

describe('explainBalanceEntry', () => {
  it('reads a positive adjustment (100 → 120 = +20)', () => {
    const e = explainBalanceEntry({ currentQuantity: '100', targetQuantity: '120', diffQuantity: '20' })
    expect(e.hasDiff).toBe(true)
    expect(e.text('AAPL')).toBe('100 → 120 → +20 AAPL')
  })

  it('reads a negative adjustment (100 → 80 = -20)', () => {
    const e = explainBalanceEntry({ currentQuantity: '100', targetQuantity: '80', diffQuantity: '-20' })
    expect(e.text('AAPL')).toBe('100 → 80 → -20 AAPL')
  })

  it('flags no diff when target equals current', () => {
    const e = explainBalanceEntry({ currentQuantity: '100', targetQuantity: '100', diffQuantity: '0' })
    expect(e.hasDiff).toBe(false)
    expect(e.diff).toBe(0)
  })

  it('derives the diff when the server omits it', () => {
    const e = explainBalanceEntry({ currentQuantity: '50', targetQuantity: '75' })
    expect(e.diff).toBe(25)
  })
})
