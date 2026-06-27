import { describe, it, expect } from 'vitest'
import { mountC } from '@/test/mount'
import AssetForm from '../AssetForm.vue'
import type { Asset } from '@/api/types'

const currencies = [{ code: 'CZK', cs: 'Koruna', en: 'Koruna' }]
const assetKinds = [{ code: 'ASSET_STOCK', cs: 'Akcie', en: 'Stock' }]

describe('AssetForm', () => {
  it('blocks submit without a symbol', async () => {
    const w = mountC(AssetForm, { props: { initial: { name: 'Apple' }, currencies, assetKinds } })
    await w.find('form').trigger('submit')
    expect(w.emitted('submit')).toBeFalsy()
    expect(w.find('[data-test=err-symbol]').exists()).toBe(true)
  })

  it('emits a camelCase asset with optional fields dropped when empty', async () => {
    const w = mountC(AssetForm, {
      props: { initial: { symbol: 'AAPL', name: 'Apple', currency: 'CZK' }, currencies, assetKinds },
    })
    await w.find('form').trigger('submit')
    const emitted = w.emitted('submit') as Array<[Asset]> | undefined
    expect(emitted).toBeTruthy()
    const asset = emitted![0]![0]
    expect(asset.symbol).toBe('AAPL')
    expect(asset.kind).toBe('ASSET_STOCK')
    expect(asset.isin).toBeUndefined()
  })
})
