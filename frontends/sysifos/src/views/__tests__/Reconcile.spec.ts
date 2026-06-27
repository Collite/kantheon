import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountC } from '@/test/mount'
import Reconcile from '../Reconcile.vue'

const reconcileMutate = vi.fn()
const decideMutate = vi.fn()
vi.mock('@/api/reconcile', () => ({
  useReconcile: () => ({ mutateAsync: reconcileMutate }),
  useDecideDiff: () => ({ mutateAsync: decideMutate }),
}))
vi.mock('@/api/portfolios', () => ({
  usePortfolios: () => ({ data: { value: { portfolios: [{ portfolioId: 'p-1', name: 'Growth' }] } } }),
}))
vi.mock('@/api/client', () => ({ bff: vi.fn().mockResolvedValue([]), BffError: class extends Error {} }))

const PORTFOLIO = '11111111-1111-4111-8111-111111111111'

describe('Reconcile', () => {
  beforeEach(() => {
    reconcileMutate.mockReset()
    decideMutate.mockReset().mockResolvedValue({})
    reconcileMutate.mockResolvedValue({
      diffs: [
        { kind: 'RECON_SYSTEM_ONLY', systemTransaction: { quantity: '10' } },
        { kind: 'RECON_STATEMENT_ONLY', statementTransaction: { quantity: '5' } },
        { kind: 'RECON_VALUE_MISMATCH', deltas: [{ field: 'quantity', systemValue: '10', statementValue: '12' }] },
      ],
      summary: { totalDiffs: 3 },
    })
  })

  it('runs reconcile and groups diffs by kind', async () => {
    const w = mountC(Reconcile)
    ;(w.vm as unknown as { portfolioId: string }).portfolioId = PORTFOLIO
    await w.vm.$nextTick()
    await w.find('[data-test=rec-run]').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(reconcileMutate).toHaveBeenCalledOnce()
    expect(w.find('[data-test=rec-rows-RECON_SYSTEM_ONLY]').exists()).toBe(true)
    expect(w.find('[data-test=rec-rows-RECON_STATEMENT_ONLY]').exists()).toBe(true)
    expect(w.find('[data-test=rec-rows-RECON_VALUE_MISMATCH]').exists()).toBe(true)
    expect(w.find('[data-test=rec-summary]').exists()).toBe(true)
  })
})
