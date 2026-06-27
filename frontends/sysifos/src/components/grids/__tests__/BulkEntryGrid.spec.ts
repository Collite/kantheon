import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountC } from '@/test/mount'
import BulkEntryGrid from '../BulkEntryGrid.vue'
import type { Asset } from '@/api/types'

const submitDraft = vi.fn()
vi.mock('@/api/drafts', () => ({ submitDraft: (...a: unknown[]) => submitDraft(...a) }))
vi.mock('@/api/client', () => ({ bff: vi.fn().mockResolvedValue([]), BffError: class extends Error {} }))

const PORTFOLIO = '11111111-1111-4111-8111-111111111111'
const ASSET = '22222222-2222-4222-8222-222222222222'
const assets: Asset[] = [{ assetId: ASSET, symbol: 'AAPL', name: 'Apple', kind: 'ASSET_STOCK', currency: 'USD' }]
const currencies = [{ code: 'USD', cs: 'Dolar', en: 'Dollar' }]
const transactionKinds = [{ code: 'TX_BUY', cs: 'Nákup', en: 'Buy' }]

function mountGrid() {
  return mountC(BulkEntryGrid, {
    props: { portfolioId: PORTFOLIO, assets, currencies, transactionKinds },
    global: { stubs: { Dialog: { template: '<div><slot /></div>' } } },
  })
}

describe('BulkEntryGrid', () => {
  beforeEach(() => {
    submitDraft.mockReset()
    submitDraft.mockResolvedValue({ draft_id: 'd-1', status: 'PENDING' })
  })

  it('fills rows from a pasted TSV block with a header', async () => {
    const w = mountGrid()
    const block = 'Symbol\tKind\tDate\tQuantity\tPrice\tFee\tCurrency\nAAPL\tTX_BUY\t2026-06-01\t10\t150\t0\tUSD'
    await w.find('[data-test=bulk-grid]').trigger('paste', {
      clipboardData: { getData: () => block },
    })
    expect(w.text()).toContain('AAPL')
    // Valid single row → commit enabled.
    const commit = w.find('[data-test=bulk-commit]')
    expect((commit.element as HTMLButtonElement).disabled).toBe(false)
  })

  it('keeps commit disabled while a row has an unknown symbol', async () => {
    const w = mountGrid()
    const block = 'Symbol\tKind\tDate\tQuantity\tPrice\tFee\tCurrency\nUNKNOWN\tTX_BUY\t2026-06-01\t10\t150\t0\tUSD'
    await w.find('[data-test=bulk-grid]').trigger('paste', { clipboardData: { getData: () => block } })
    expect((w.find('[data-test=bulk-commit]').element as HTMLButtonElement).disabled).toBe(true)
    // The "resolve unknown" affordance shows.
    expect(w.find('[data-test=bulk-resolve]').exists()).toBe(true)
  })

  it('submits a DRAFT_TRANSACTION_BATCH on commit', async () => {
    const w = mountGrid()
    const block = 'Symbol\tKind\tDate\tQuantity\tPrice\tFee\tCurrency\nAAPL\tTX_BUY\t2026-06-01\t10\t150\t0\tUSD'
    await w.find('[data-test=bulk-grid]').trigger('paste', { clipboardData: { getData: () => block } })
    await w.find('[data-test=bulk-commit]').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(submitDraft).toHaveBeenCalledOnce()
    const [kind, payload] = submitDraft.mock.calls[0]!
    expect(kind).toBe('DRAFT_TRANSACTION_BATCH')
    expect((payload as { rows: unknown[] }).rows).toHaveLength(1)
  })
})
