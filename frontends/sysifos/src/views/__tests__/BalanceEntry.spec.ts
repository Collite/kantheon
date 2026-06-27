import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountC } from '@/test/mount'
import BalanceEntry from '../BalanceEntry.vue'

const previewMutate = vi.fn()

// Mock the data edges so the screen logic (validation, explanation, no-diff gating)
// is exercised without a live BFF.
vi.mock('@/api/balanceEntries', () => ({
  usePreviewBalanceEntry: () => ({ mutateAsync: previewMutate }),
  useCommitBalanceEntry: () => ({ mutateAsync: vi.fn() }),
  useBalanceHistory: () => ({ data: { value: { transactions: [] } } }),
}))
vi.mock('@/api/portfolios', () => ({
  usePortfolios: () => ({ data: { value: { portfolios: [{ portfolioId: 'p-1', name: 'Growth' }] } } }),
}))
vi.mock('@/api/assets', () => ({
  useAssets: () => ({ data: { value: { assets: [{ assetId: 'a-1', symbol: 'AAPL' }] } } }),
}))
vi.mock('@/api/client', () => ({ bff: vi.fn().mockResolvedValue([]), BffError: class extends Error {} }))

const PORTFOLIO = '11111111-1111-4111-8111-111111111111'
const ASSET = '22222222-2222-4222-8222-222222222222'

describe('BalanceEntry', () => {
  beforeEach(() => previewMutate.mockReset())

  it('blocks preview and flags fields when the form is invalid', async () => {
    const w = mountC(BalanceEntry)
    await w.find('[data-test=be-preview]').trigger('click')
    expect(previewMutate).not.toHaveBeenCalled()
    expect(w.find('[data-test=err-portfolio]').exists()).toBe(true)
  })

  it('renders the adjustment explanation for a non-zero diff', async () => {
    previewMutate.mockResolvedValue({ currentQuantity: '100', targetQuantity: '120', diffQuantity: '20', assetId: ASSET })
    const w = mountC(BalanceEntry)
    const vm = w.vm as unknown as {
      portfolioId: string
      assetId: string
      targetQuantity: string
      asOf: Date
    }
    vm.portfolioId = PORTFOLIO
    vm.assetId = ASSET
    vm.targetQuantity = '120'
    await w.find('[data-test=be-preview]').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(previewMutate).toHaveBeenCalledOnce()
    expect(w.find('[data-test=be-explain]').exists()).toBe(true)
    expect(w.find('[data-test=be-commit]').exists()).toBe(true)
  })

  it('shows a friendly no-diff note and hides Commit when target == current', async () => {
    previewMutate.mockResolvedValue({ currentQuantity: '100', targetQuantity: '100', diffQuantity: '0' })
    const w = mountC(BalanceEntry)
    const vm = w.vm as unknown as { portfolioId: string; assetId: string; targetQuantity: string }
    vm.portfolioId = PORTFOLIO
    vm.assetId = ASSET
    vm.targetQuantity = '100'
    await w.find('[data-test=be-preview]').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(w.find('[data-test=be-nodiff]').exists()).toBe(true)
    expect(w.find('[data-test=be-commit]').exists()).toBe(false)
  })
})
