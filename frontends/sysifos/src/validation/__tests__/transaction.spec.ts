import { describe, it, expect } from 'vitest'
import { parsePastedBlock, validateCell, rowValid, rowToForm, emptyRow } from '@/validation/transaction'

describe('parsePastedBlock', () => {
  it('maps columns by a detected header row (en + cs aliases)', () => {
    const block = 'Symbol\tKind\tDate\tQuantity\tPrice\tFee\tCurrency\nAAPL\tTX_BUY\t2026-06-01\t10\t150\t1\tUSD'
    const rows = parsePastedBlock(block)
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({ symbol: 'AAPL', kind: 'TX_BUY', tradeDate: '2026-06-01', quantity: '10', price: '150' })
  })

  it('falls back to positional order when there is no header', () => {
    const block = 'AAPL\tTX_BUY\t2026-06-01\t10\t150\t0\tUSD\nMSFT\tTX_SELL\t2026-06-02\t5\t300\t0\tUSD'
    const rows = parsePastedBlock(block)
    expect(rows).toHaveLength(2)
    expect(rows[1]?.symbol).toBe('MSFT')
    expect(rows[1]?.kind).toBe('TX_SELL')
  })

  it('skips blank lines and pads short rows', () => {
    const block = 'AAPL\tTX_BUY\n\nMSFT\tTX_SELL\n'
    const rows = parsePastedBlock(block)
    expect(rows).toHaveLength(2)
    expect(rows[0]?.quantity).toBe('') // padded
  })
})

describe('validateCell', () => {
  it('flags a non-numeric quantity and a zero quantity', () => {
    expect(validateCell('quantity', 'abc')).toBe('number')
    expect(validateCell('quantity', '0')).toBe('must not be 0')
    expect(validateCell('quantity', '10')).toBeNull()
  })
  it('requires an ISO date and a 3-letter currency', () => {
    expect(validateCell('tradeDate', '01/06/2026')).toBe('YYYY-MM-DD')
    expect(validateCell('tradeDate', '2026-06-01')).toBeNull()
    expect(validateCell('currency', 'czk')).toBe('ISO 4217')
    expect(validateCell('currency', 'CZK')).toBeNull()
  })
  it('treats an empty fee as valid (optional)', () => {
    expect(validateCell('fee', '')).toBeNull()
  })
})

describe('rowToForm', () => {
  const PORTFOLIO = '11111111-1111-4111-8111-111111111111'
  const ASSET = '22222222-2222-4222-8222-222222222222'

  it('builds a security-leg TransactionForm with an RFC3339 trade date', () => {
    const row = { ...emptyRow('USD'), symbol: 'AAPL', assetId: ASSET, quantity: '10', price: '150', tradeDate: '2026-06-01' }
    expect(rowValid(row)).toBe(true)
    const form = rowToForm(row, PORTFOLIO)
    expect(form.portfolioId).toBe(PORTFOLIO)
    expect(form.assetId).toBe(ASSET)
    expect(form.tradeDate).toBe('2026-06-01T00:00:00Z')
    expect(form.price?.amount).toBe('150')
  })
})
