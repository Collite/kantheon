import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountC } from '@/test/mount'
import AssetQuickCreate from '../AssetQuickCreate.vue'
import type { Asset } from '@/api/types'

const mutateAsync = vi.fn()

// Mock the network edges: the create mutation + the dictionaries fetch.
vi.mock('@/api/assets', () => ({
  useCreateAsset: () => ({ mutateAsync }),
}))
vi.mock('@/api/client', () => ({
  bff: vi.fn().mockResolvedValue([{ code: 'CZK', cs: 'Koruna', en: 'Koruna' }]),
  BffError: class extends Error {},
}))

describe('AssetQuickCreate', () => {
  beforeEach(() => {
    mutateAsync.mockReset()
    mutateAsync.mockResolvedValue({ asset: { assetId: 'a-new', symbol: 'TSLA', name: 'Tesla' } as Asset })
  })

  it('prefills the typed symbol into the embedded form', () => {
    const w = mountC(AssetQuickCreate, {
      props: { visible: true, symbol: 'TSLA' },
      global: { stubs: { Dialog: { template: '<div><slot /></div>' } } },
    })
    const input = w.find('[data-test=asset-symbol]').element as HTMLInputElement
    expect(input.value).toBe('TSLA')
  })

  it('writes the asset and emits it back to the caller on submit', async () => {
    const w = mountC(AssetQuickCreate, {
      props: { visible: true, symbol: 'TSLA' },
      global: { stubs: { Dialog: { template: '<div><slot /></div>' } } },
    })
    await w.find('[data-test=asset-name]').setValue('Tesla')
    await w.find('form').trigger('submit')
    await new Promise((r) => setTimeout(r, 0))
    expect(mutateAsync).toHaveBeenCalledOnce()
    const created = w.emitted('created') as Array<[Asset]> | undefined
    expect(created).toBeTruthy()
    expect(created![0]![0].assetId).toBe('a-new')
  })
})
